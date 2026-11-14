package works.bosk.drivers.mongo.internal;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoException;
import com.mongodb.MongoNamespace;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.TransactionBody;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import works.bosk.Bosk;
import works.bosk.logging.MdcKeys;

import static com.mongodb.ReadConcern.LOCAL;

/**
 * A wrapper for {@link MongoCollection} that manages a thread-local
 * {@link ClientSession} so that operations are bound to the current session
 * without needing to pass it explicitly. It also runs a body in a transaction
 * managed by the client library when the caller asks for one.
 */
@SuppressWarnings("NullableProblems")
@RequiredArgsConstructor(staticName = "of")
class SessionCollection {
	private final MongoCollection<BsonDocument> downstream;
	private final MongoClient mongoClient;
	private final FindInterceptor findInterceptor;
	private final WriteInterceptor writeInterceptor;
	private final CommitInterceptor commitInterceptor;
	private final SessionInterceptor sessionInterceptor;
	private final ThreadLocal<Session> currentSession = new ThreadLocal<>();

	/**
	 * A {@code SessionCollection} with no read, write, or commit interposition.
	 */
	static SessionCollection of(MongoCollection<BsonDocument> downstream, MongoClient mongoClient) {
		return of(downstream, mongoClient, FindInterceptor.identity(), WriteInterceptor.identity(), CommitInterceptor.identity(), SessionInterceptor.identity());
	}

	/**
	 * The kind of session to open, which determines the options it's opened
	 * with and the operations it permits.
	 */
	enum SessionType {
		/**
		 * Writes and transactions are allowed.
		 */
		WRITEABLE,
		/**
		 * All reads see the same snapshot of the database. Writes and
		 * transactions are rejected.
		 */
		READ_ONLY;

		boolean isReadOnly() {
			return this != WRITEABLE;
		}
	}

	public Session newSession() throws FailedMongoClientSessionException {
		return new Session(SessionType.WRITEABLE);
	}

	/**
	 * Creates a read-only session whose reads all see the same snapshot of the
	 * database.
	 * <p>
	 * The session takes its snapshot on its first read, and every subsequent
	 * read uses that same snapshot. {@link #establishSnapshot()} establishes the
	 * snapshot deliberately, and reports the time it corresponds to.
	 * <p>
	 * A read-only session can't contain a transaction or perform a write.
	 */
	public Session newReadOnlySession() throws FailedMongoClientSessionException {
		return new Session(SessionType.READ_ONLY);
	}

	/**
	 * Runs {@code body} in a transaction managed by MongoDB's client library
	 * via {@link ClientSession#withTransaction}, which owns
	 * starting the transaction, retrying it on transient errors, and committing it.
	 * <p>
	 * The caller must already have an open writable {@link Session}. The body runs
	 * with that session current on this thread, so an
	 * {@link #ensureTransactionStarted()} call within the body will see the
	 * driver's transaction already in progress and leave it alone.
	 * <p>
	 * Because the client library commits internally, this bypasses
	 * {@link Session#commitTransactionIfAny()} and therefore the
	 * {@link CommitInterceptor}.
	 */
	public <T> T withTransaction(TransactionOptions options, TransactionBody<T> body) {
		Session session = currentSession.get();
		if (session == null) {
			throw new IllegalStateException("No active session");
		} else if (session.type.isReadOnly()) {
			throw new IllegalStateException("Cannot write in a read-only session");
		}
		return session.clientSession.withTransaction(body, options);
	}

	/**
	 * An abstraction for a thread-local {@link ClientSession}.
	 * Logic that must run in a transaction can call {@link #ensureTransactionStarted()},
	 * in which case the transaction will be aborted at the end of the session
	 * unless {@link #commitTransactionIfAny()} is called first.
	 */
	public class Session implements AutoCloseable {
		final ClientSession clientSession;
		final SessionType type;
		final String name;
		final String oldMDC;
		static final AtomicLong identityCounter = new AtomicLong(1);

		public Session(SessionType type) throws FailedMongoClientSessionException {
			this.type = type;
			name = (type.isReadOnly()? "r":"s") + identityCounter.getAndIncrement();
			oldMDC = MDC.get(MdcKeys.TRANSACTION);
			if (currentSession.get() != null) {
				// Note: we don't throw FailedMongoClientSessionException because this
				// is not a transient exception that can be remedied by waiting
				// and retrying (which is what callers typically do when they
				// catch FailedMongoClientSessionException). This isn't a "failure" so much
				// as a bosk bug!
				throw new IllegalStateException("Cannot start nested session");
			}
			ClientSessionOptions.Builder sessionOptions = ClientSessionOptions.builder()
				.defaultTransactionOptions(TransactionOptions.builder()
					.writeConcern(WriteConcern.MAJORITY)
					.readConcern(ReadConcern.MAJORITY)
					.readPreference(ReadPreference.primary())
					.build());
			if (type.isReadOnly()) {
				// Snapshot reads and causal consistency are mutually exclusive.
				sessionOptions.snapshot(true);
			} else {
				sessionOptions.causallyConsistent(true);
			}
			try {
				this.clientSession = mongoClient.startSession(sessionOptions.build());
			} catch (RuntimeException | Error e) {
				throw new FailedMongoClientSessionException(e);
			}
			currentSession.set(this);
			MDC.put(MdcKeys.TRANSACTION, name);
			LOGGER.debug("Begin session {} lsid={} type={}", name, lsid(), type);
		}

		/**
		 * Ends the active transaction; a subsequent call to {@link #ensureTransactionStarted()}
		 * would start a new transaction.
		 * <p>
		 * If the commit returns an {@link MongoException} with the
		 * {@link MongoException#UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL} error label,
		 * the commit is retried, since the transaction may or may not have been committed.
		 * If the result is still unknown after the retries, the {@link MongoException} is rethrown
		 * so the caller can resolve the indeterminate outcome.
		 * <p>
		 * The retry is driven by the outcome of {@code commitTransaction()}, not by
		 * {@code hasActiveTransaction()}: the MongoDB driver marks the transaction committed
		 * even when the commit throws, so {@code hasActiveTransaction()} returns {@code false}
		 * after a failed commit too, and checking it would make the retry unreachable.
		 */
		public void commitTransactionIfAny() {
			if (clientSession.hasActiveTransaction()) {
				int retriesRemaining = 2;
				while (true) {
					LOGGER.debug("Commit transaction {} lsid={} txnNumber={}", name, lsid(), txnNumber());
					try {
						clientSession.commitTransaction();
						commitInterceptor.afterCommitAttempt();
						break;
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
		}

		@Override
		public void close() {
			// Cleanup must not be defeated by an interrupt: the MongoDB driver's
			// abortTransaction() silently gives up if it can't reach the server,
			// which would leave the transaction open until the connection or the
			// server reaps it. Interrupts are expected here (shutdown and test
			// timeouts interrupt threads), so clear the flag while we abort and
			// close, then restore it.
			boolean interrupted = Thread.interrupted();
			try {
				boolean hadActiveTransaction = clientSession.hasActiveTransaction();
				try {
					if (hadActiveTransaction) {
						LOGGER.debug("Unsuccessful session; aborting transaction");
						sessionInterceptor.beforeAbortAttempt();
						clientSession.abortTransaction();
					}
				} finally {
					// The MongoDB driver's own ClientSession.close() aborts any active
					// transaction, so we must close it even if the explicit abort above
					// failed.
					LOGGER.debug("Close session {} active={} interrupted={} lsid={}",
						name, hadActiveTransaction, interrupted, lsid());
					try {
						clientSession.close();
					} finally {
						currentSession.remove();
						MDC.put(MdcKeys.TRANSACTION, oldMDC);
					}
				}
			} finally {
				if (interrupted) {
					Thread.currentThread().interrupt();
				}
			}
		}

		/**
		 * The MongoDB server session id, for correlating this session with
		 * the database's own view (for example, the transactions reported by
		 * {@code currentOp}). Intended for {@code debug} logs.
		 */
		private String lsid() {
			return String.valueOf(clientSession.getServerSession().getIdentifier());
		}

		/**
		 * The current transaction number, for correlating this session's
		 * transactions with the database's own view. Intended for {@code debug} logs.
		 */
		private long txnNumber() {
			return clientSession.getServerSession().getTransactionNumber();
		}

		private static final Logger LOGGER = LoggerFactory.getLogger(Session.class);
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
		} else if (session.type.isReadOnly()) {
			throw new IllegalStateException("Cannot start a transaction in a read-only session");
		} else if (!session.clientSession.hasActiveTransaction()) {
			session.clientSession.startTransaction();
			LOGGER.debug("Start transaction {} lsid={} txnNumber={}", session.name, session.lsid(), session.txnNumber());
		}
	}

	/**
	 * Establishes the current session's snapshot, and returns the time it
	 * corresponds to.
	 * <p>
	 * The first read in a read-only session establishes the snapshot, so this
	 * read exists only to learn when that happened. The database reports the
	 * time as {@code atClusterTime} in the response; the client library reads
	 * the same field but doesn't expose it publicly.
	 * <p>
	 * The read is an empty aggregation, so it touches no collection. A read of
	 * a collection that doesn't exist yet reports no time, and during
	 * initialization the collection may not exist.
	 */
	public BsonTimestamp establishSnapshot() {
		Session session = requireReadOnlySession();
		MongoNamespace namespace = downstream.getNamespace();
		BsonDocument aggregate = new BsonDocument()
			.append("aggregate", new BsonInt32(1))
			.append("pipeline", new BsonArray(List.of(
				new BsonDocument("$documents", new BsonArray()))))
			.append("cursor", new BsonDocument());
		LOGGER.debug("Establishing snapshot with {}", aggregate);
		Document response = mongoClient
			.getDatabase(namespace.getDatabaseName())
			.runCommand(session.clientSession, aggregate);
		Document cursor = response.get("cursor", Document.class);
		BsonTimestamp snapshotTime = cursor == null ? null : cursor.get("atClusterTime", BsonTimestamp.class);
		if (snapshotTime == null) {
			throw new IllegalStateException("Snapshot read did not report a snapshot time: " + response);
		}
		LOGGER.debug("Snapshot established at {}", snapshotTime);
		return snapshotTime;
	}

	private Session requireReadOnlySession() {
		Session session = currentSession.get();
		if (session == null) {
			throw new IllegalStateException("No active session");
		} else if (!session.type.isReadOnly()) {
			throw new IllegalStateException("Operation requires a read-only session");
		} else {
			return session;
		}
	}

	/**
	 * Fails if the current thread is inside a transaction.
	 * <p>
	 * Use this for operations that must not hold a transaction while doing
	 * something slow or non-database-related. A thread with no session at all
	 * has no transaction, so that case is allowed.
	 */
	public void requireNoTransaction() {
		Session session = currentSession.get();
		if (session != null && session.clientSession.hasActiveTransaction()) {
			throw new IllegalStateException("Operation must not run inside a transaction");
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
	 */
	public void commitTransactionIfAny() {
		LOGGER.debug("Commit transaction");
		currentSession.get().commitTransactionIfAny();
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

	/**
	 * Read the most recent value from the database regardless of the current session.
	 * Uses {@link ReadConcern#LOCAL} and bypasses any session/transaction in progress.
	 * Analogous to {@link Bosk#supersedingReadSession()} in that it bypasses the usual
	 * determinism features and gives direct access to the very latest data.
	 * <p>
	 * This is appropriate for reading a single document, like a root document's
	 * revision for {@link FormatDriver#flush()}. A {@code findLatest} cursor reads
	 * each document at its own instant, so it is <em>not</em> appropriate for a
	 * multi-document read: use {@link #find} within a transaction, which reads the
	 * transaction's snapshot.
	 * <p>
	 * It's probably not appropriate for ordinary updates, which benefit from the
	 * determinism and reproducibility of sessions and transactions.
	 */
	public FindBuilder findLatest(Bson filter) {
		return new FindBuilder(this.downstream.withReadConcern(LOCAL).find(filter), filter, findInterceptor);
	}

	public FindBuilder find(Bson filter) {
		return new FindBuilder(this.downstream.find(currentSession(), filter), filter, findInterceptor);
	}

	public long countDocuments(Bson filter, CountOptions options) {
		return this.downstream.countDocuments(currentSession(), filter, options);
	}

	public InsertOneResult insertOne(BsonDocument document) {
		requireWritable();
		writeInterceptor.beforeWrite(new BsonDocument("_id", document.get("_id")));
		return this.downstream.insertOne(currentSession(), document);
	}

	public DeleteResult deleteOne(Bson filter) {
		requireWritable();
		writeInterceptor.beforeWrite(filter);
		return this.downstream.deleteOne(currentSession(), filter);
	}

	public DeleteResult deleteMany(Bson filter) {
		requireWritable();
		writeInterceptor.beforeWrite(filter);
		return this.downstream.deleteMany(currentSession(), filter);
	}

	public UpdateResult replaceOne(Bson filter, BsonDocument replacement, ReplaceOptions replaceOptions) {
		requireWritable();
		writeInterceptor.beforeWrite(filter);
		return this.downstream.replaceOne(currentSession(), filter, replacement, replaceOptions);
	}

	public UpdateResult updateOne(Bson filter, Bson update) {
		requireWritable();
		writeInterceptor.beforeWrite(filter);
		return this.downstream.updateOne(currentSession(), filter, update);
	}

	public UpdateResult updateOne(Bson filter, Bson update, UpdateOptions updateOptions) {
		requireWritable();
		writeInterceptor.beforeWrite(filter);
		return this.downstream.updateOne(currentSession(), filter, update, updateOptions);
	}

	private void requireWritable() {
		Session session = currentSession.get();
		if (session == null) {
			throw new IllegalStateException("No active session");
		} else if (session.type.isReadOnly()) {
			throw new IllegalStateException("Cannot write in a read-only session");
		}
	}

	/**
	 * A fluent read query over the collection, mirroring the subset of
	 * {@link FindIterable}'s API that the driver uses. Like
	 * {@link FindIterable}, the query executes lazily: the database is not
	 * contacted until {@link #cursor()} is called.
	 */
	public static final class FindBuilder {
		private final Bson filter;
		private final FindInterceptor findInterceptor;
		private FindIterable<BsonDocument> downstream;
		private ReadOptions options = ReadOptions.none();

		FindBuilder(FindIterable<BsonDocument> downstream, Bson filter, FindInterceptor findInterceptor) {
			this.downstream = downstream;
			this.filter = filter;
			this.findInterceptor = findInterceptor;
		}

		public FindBuilder sort(Bson sort) {
			this.downstream = downstream.sort(sort);
			this.options = options.withSort(sort);
			return this;
		}

		public FindBuilder limit(int limit) {
			this.downstream = downstream.limit(limit);
			this.options = options.withLimit(limit);
			return this;
		}

		public FindBuilder projection(Bson projection) {
			this.downstream = downstream.projection(projection);
			this.options = options.withProjection(projection);
			return this;
		}

		public DocCursor cursor() {
			return findInterceptor.intercept(filter, options, new MongoCursorAdapter(downstream.cursor()));
		}
	}

	private static final class MongoCursorAdapter implements DocCursor {
		private final MongoCursor<BsonDocument> downstream;

		MongoCursorAdapter(MongoCursor<BsonDocument> downstream) {
			this.downstream = downstream;
		}

		@Override
		public boolean hasNext() {
			return downstream.hasNext();
		}

		@Override
		public BsonDocument next() {
			return downstream.next();
		}

		@Override
		public void close() {
			downstream.close();
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(SessionCollection.class);
}
