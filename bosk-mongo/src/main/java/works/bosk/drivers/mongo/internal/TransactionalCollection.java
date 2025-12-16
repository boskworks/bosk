package works.bosk.drivers.mongo.internal;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import works.bosk.logging.MdcKeys;

/**
 * A wrapper for {@link MongoCollection} that manages a thread-local
 * {@link ClientSession} so that operations can implicitly participate
 * in transactions without needing to do so explicitly.
 */
@SuppressWarnings("NullableProblems")
@RequiredArgsConstructor(staticName = "of")
class TransactionalCollection {
	private final MongoCollection<BsonDocument> downstream;
	private final MongoClient mongoClient;
	private final ThreadLocal<Session> currentSession = new ThreadLocal<>();
	private static final AtomicLong identityCounter = new AtomicLong(1);

	public Session newSession() throws FailedSessionException {
		return new Session(false);
	}

	public Session newReadOnlySession() throws FailedSessionException {
		return new Session(true);
	}

	/**
	 * An abstraction for a thread-local {@link ClientSession}.
	 * Logic that must run in a transaction can call {@link #ensureTransactionStarted()},
	 * in which case the transaction will be aborted at the end of the session
	 * unless {@link #commitTransactionIfAny()} is called first.
	 */
	public class Session implements AutoCloseable {
		final ClientSession clientSession;
		final boolean isReadOnly;
		final String name;
		final String oldMDC;

		public Session(boolean isReadOnly) throws FailedSessionException {
			this.isReadOnly = isReadOnly;
			name = (isReadOnly? "r":"s") + identityCounter.getAndIncrement();
			oldMDC = MDC.get(MdcKeys.TRANSACTION);
			if (currentSession.get() != null) {
				// Note: we don't throw FailedSessionException because this
				// is not a transient exception that can be remedied by waiting
				// and retrying (which is what callers typically do when they
				// catch FailedSessionException). This isn't a "failure" so much
				// as a bosk bug!
				throw new IllegalStateException("Cannot start nested session");
			}
			ClientSessionOptions sessionOptions = ClientSessionOptions.builder()
				.causallyConsistent(true)
				.defaultTransactionOptions(TransactionOptions.builder()
					.writeConcern(WriteConcern.MAJORITY)
					.readConcern(ReadConcern.MAJORITY)
					.readPreference(ReadPreference.primary())
					.build())
				.build();
			try {
				this.clientSession = mongoClient.startSession(sessionOptions);
			} catch (RuntimeException | Error e) {
				throw new FailedSessionException(e);
			}
			currentSession.set(this);
			MDC.put(MdcKeys.TRANSACTION, name);
			LOGGER.debug("Begin session");
		}

		/**
		 * Ends the active transaction; a subsequent call to {@link #ensureTransactionStarted()}
		 * would start a new transaction.
		 */
		public void commitTransactionIfAny() {
			int retriesRemaining = 2;
			while (clientSession.hasActiveTransaction()) {
				LOGGER.debug("Commit transaction");
				try {
					clientSession.commitTransaction();
				} catch (MongoException e) {
					if (e.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
						if (retriesRemaining >= 1) {
							retriesRemaining--;
							LOGGER.debug("Unknown transaction commit result; retrying the commit", e);
						} else {
							LOGGER.debug("Exhausted commit retry attempts", e);
							throw e;
						}
					} else {
						LOGGER.debug("Can't retry commit; rethrowing", e);
						throw e;
					}
				}
			}
		}

		@Override
		public void close() {
			if (clientSession.hasActiveTransaction()) {
				LOGGER.debug("Unsuccessful session; aborting transaction");
				clientSession.abortTransaction();
			}
			LOGGER.debug("Close session");
			clientSession.close();
			currentSession.remove();
			MDC.put(MdcKeys.TRANSACTION, oldMDC);
		}

	}

	/**
	 * Ensures that there is an active transaction on the current thread.
	 * If called when there's already an active transaction, has no effect.
	 * <p>
	 * This allows us to write composable database functionality by allowing
	 * each method to declare that it must occur inside a transaction without
	 * insisting on a particular transaction boundary location. If method A
	 * calls method B, and both call this method, then both will occur in the
	 * same transaction.
	 */
	public void ensureTransactionStarted() {
		Session session = currentSession.get();
		if (session == null) {
			throw new IllegalStateException("No active session");
		} else if (session.isReadOnly) {
			throw new IllegalStateException("Cannot execute a transaction in a read-only session");
		} else if (!session.clientSession.hasActiveTransaction()) {
			session.clientSession.startTransaction();
			LOGGER.debug("Start transaction");
		}
	}

	/**
	 * This method kind of breaks the abstraction we're trying to build here.
	 * <p>
	 * Calling this is not usually recommended because it ends the transaction,
	 * which defeats the intent of the prior {@link #ensureTransactionStarted()} call;
	 * and it's not often needed anyway, because whoever opened the {@link Session} will
	 * call {@link Session#commitTransactionIfAny()} on successful completion of the session.
	 * <p>
	 * However, if there are non-database actions that must occur after the commit,
	 * this can be called first to ensure a successful commit before proceeding.
	 * Just be aware that whoever called {@link #ensureTransactionStarted()} might not
	 * have expected you to have ended their transaction: they might have pending
	 * updates they don't expect to be committed, or they may do subsequent operations
	 * and then expect to be able to roll them back.
	 *
	 * @throws IllegalStateException if there's no active transaction
	 */
	public void commitTransaction() {
		LOGGER.debug("Commit transaction");
		Session session = currentSession.get();
		if (session.clientSession.hasActiveTransaction()) {
			session.commitTransactionIfAny();
		} else {
			throw new IllegalStateException("No active transaction");
		}
	}

	public void abortTransaction() {
		LOGGER.debug("Abort transaction");
		currentSession().abortTransaction();
	}

	private ClientSession currentSession() {
		Session session = currentSession.get();
		if (session == null) {
			throw new IllegalStateException("No active session");
		} else {
			return session.clientSession;
		}
	}

	public MongoCollection<BsonDocument> withReadConcern(ReadConcern readConcern) {
		return this.downstream.withReadConcern(readConcern);
	}

	public long countDocuments(Bson filter, CountOptions options) {
		return this.downstream.countDocuments(currentSession(), filter, options);
	}

	public FindIterable<BsonDocument> find(Bson filter) {
		return this.downstream.find(currentSession(), filter);
	}

	public InsertOneResult insertOne(BsonDocument document) {
		return this.downstream.insertOne(currentSession(), document);
	}

	public DeleteResult deleteOne(Bson filter) {
		return this.downstream.deleteOne(currentSession(), filter);
	}

	public DeleteResult deleteMany(Bson filter) {
		return this.downstream.deleteMany(currentSession(), filter);
	}

	public UpdateResult replaceOne(Bson filter, BsonDocument replacement, ReplaceOptions replaceOptions) {
		return this.downstream.replaceOne(currentSession(), filter, replacement, replaceOptions);
	}

	public UpdateResult updateOne(Bson filter, Bson update) {
		return this.downstream.updateOne(currentSession(), filter, update);
	}

	public UpdateResult updateOne(Bson filter, Bson update, UpdateOptions updateOptions) {
		return this.downstream.updateOne(currentSession(), filter, update, updateOptions);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalCollection.class);
}
