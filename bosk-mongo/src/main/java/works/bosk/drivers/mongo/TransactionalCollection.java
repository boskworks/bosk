package works.bosk.drivers.mongo;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoException;
import com.mongodb.MongoNamespace;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.ClientSession;
import com.mongodb.client.DistinctIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.ListSearchIndexesIterable;
import com.mongodb.client.MapReduceIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.CreateIndexOptions;
import com.mongodb.client.model.DeleteOptions;
import com.mongodb.client.model.DropCollectionOptions;
import com.mongodb.client.model.DropIndexOptions;
import com.mongodb.client.model.EstimatedDocumentCountOptions;
import com.mongodb.client.model.FindOneAndDeleteOptions;
import com.mongodb.client.model.FindOneAndReplaceOptions;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.InsertOneOptions;
import com.mongodb.client.model.RenameCollectionOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.SearchIndexModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
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
class TransactionalCollection<TDocument> implements MongoCollection<TDocument> {
	private final MongoCollection<TDocument> downstream;
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

	public MongoNamespace getNamespace() {
		return this.downstream.getNamespace();
	}

	public Class<TDocument> getDocumentClass() {
		return this.downstream.getDocumentClass();
	}

	public CodecRegistry getCodecRegistry() {
		return this.downstream.getCodecRegistry();
	}

	public ReadPreference getReadPreference() {
		return this.downstream.getReadPreference();
	}

	public WriteConcern getWriteConcern() {
		return this.downstream.getWriteConcern();
	}

	public ReadConcern getReadConcern() {
		return this.downstream.getReadConcern();
	}

	@Override
	public Long getTimeout(TimeUnit timeUnit) {
		return this.downstream.getTimeout(timeUnit);
	}

	public <NewTDocument> MongoCollection<NewTDocument> withDocumentClass(Class<NewTDocument> clazz) {
		return this.downstream.withDocumentClass(clazz);
	}

	public MongoCollection<TDocument> withCodecRegistry(CodecRegistry codecRegistry) {
		return this.downstream.withCodecRegistry(codecRegistry);
	}

	public MongoCollection<TDocument> withReadPreference(ReadPreference readPreference) {
		return this.downstream.withReadPreference(readPreference);
	}

	public MongoCollection<TDocument> withWriteConcern(WriteConcern writeConcern) {
		return this.downstream.withWriteConcern(writeConcern);
	}

	public MongoCollection<TDocument> withReadConcern(ReadConcern readConcern) {
		return this.downstream.withReadConcern(readConcern);
	}

	@Override
	public MongoCollection<TDocument> withTimeout(long timeout, TimeUnit timeUnit) {
		return this.downstream.withTimeout(timeout, timeUnit);
	}

	public long countDocuments() {
		return this.downstream.countDocuments(currentSession());
	}

	public long countDocuments(Bson filter) {
		return this.downstream.countDocuments(currentSession(), filter);
	}

	public long countDocuments(Bson filter, CountOptions options) {
		return this.downstream.countDocuments(currentSession(), filter, options);
	}

	public long countDocuments(ClientSession clientSession) {
		return this.downstream.countDocuments(clientSession);
	}

	public long countDocuments(ClientSession clientSession, Bson filter) {
		return this.downstream.countDocuments(clientSession, filter);
	}

	public long countDocuments(ClientSession clientSession, Bson filter, CountOptions options) {
		return this.downstream.countDocuments(clientSession, filter, options);
	}

	public long estimatedDocumentCount() {
		return this.downstream.estimatedDocumentCount();
	}

	public long estimatedDocumentCount(EstimatedDocumentCountOptions options) {
		return this.downstream.estimatedDocumentCount(options);
	}

	public <TResult> DistinctIterable<TResult> distinct(String fieldName, Class<TResult> resultClass) {
		return this.downstream.distinct(currentSession(), fieldName, resultClass);
	}

	public <TResult> DistinctIterable<TResult> distinct(String fieldName, Bson filter, Class<TResult> resultClass) {
		return this.downstream.distinct(currentSession(), fieldName, filter, resultClass);
	}

	public <TResult> DistinctIterable<TResult> distinct(ClientSession clientSession, String fieldName, Class<TResult> resultClass) {
		return this.downstream.distinct(clientSession, fieldName, resultClass);
	}

	public <TResult> DistinctIterable<TResult> distinct(ClientSession clientSession, String fieldName, Bson filter, Class<TResult> resultClass) {
		return this.downstream.distinct(clientSession, fieldName, filter, resultClass);
	}

	public FindIterable<TDocument> find() {
		return this.downstream.find(currentSession());
	}

	public <TResult> FindIterable<TResult> find(Class<TResult> resultClass) {
		return this.downstream.find(currentSession(), resultClass);
	}

	public FindIterable<TDocument> find(Bson filter) {
		return this.downstream.find(currentSession(), filter);
	}

	public <TResult> FindIterable<TResult> find(Bson filter, Class<TResult> resultClass) {
		return this.downstream.find(currentSession(), filter, resultClass);
	}

	public FindIterable<TDocument> find(ClientSession clientSession) {
		return this.downstream.find(clientSession);
	}

	public <TResult> FindIterable<TResult> find(ClientSession clientSession, Class<TResult> resultClass) {
		return this.downstream.find(clientSession, resultClass);
	}

	public FindIterable<TDocument> find(ClientSession clientSession, Bson filter) {
		return this.downstream.find(clientSession, filter);
	}

	public <TResult> FindIterable<TResult> find(ClientSession clientSession, Bson filter, Class<TResult> resultClass) {
		return this.downstream.find(clientSession, filter, resultClass);
	}

	public AggregateIterable<TDocument> aggregate(List<? extends Bson> pipeline) {
		return this.downstream.aggregate(currentSession(), pipeline);
	}

	public <TResult> AggregateIterable<TResult> aggregate(List<? extends Bson> pipeline, Class<TResult> resultClass) {
		return this.downstream.aggregate(currentSession(), pipeline, resultClass);
	}

	public AggregateIterable<TDocument> aggregate(ClientSession clientSession, List<? extends Bson> pipeline) {
		return this.downstream.aggregate(clientSession, pipeline);
	}

	public <TResult> AggregateIterable<TResult> aggregate(ClientSession clientSession, List<? extends Bson> pipeline, Class<TResult> resultClass) {
		return this.downstream.aggregate(clientSession, pipeline, resultClass);
	}

	public ChangeStreamIterable<TDocument> watch() {
		return this.downstream.watch(currentSession());
	}

	public <TResult> ChangeStreamIterable<TResult> watch(Class<TResult> resultClass) {
		return this.downstream.watch(currentSession(), resultClass);
	}

	public ChangeStreamIterable<TDocument> watch(List<? extends Bson> pipeline) {
		return this.downstream.watch(currentSession(), pipeline);
	}

	public <TResult> ChangeStreamIterable<TResult> watch(List<? extends Bson> pipeline, Class<TResult> resultClass) {
		return this.downstream.watch(currentSession(), pipeline, resultClass);
	}

	public ChangeStreamIterable<TDocument> watch(ClientSession clientSession) {
		return this.downstream.watch(clientSession);
	}

	public <TResult> ChangeStreamIterable<TResult> watch(ClientSession clientSession, Class<TResult> resultClass) {
		return this.downstream.watch(clientSession, resultClass);
	}

	public ChangeStreamIterable<TDocument> watch(ClientSession clientSession, List<? extends Bson> pipeline) {
		return this.downstream.watch(clientSession, pipeline);
	}

	public <TResult> ChangeStreamIterable<TResult> watch(ClientSession clientSession, List<? extends Bson> pipeline, Class<TResult> resultClass) {
		return this.downstream.watch(clientSession, pipeline, resultClass);
	}

	@SuppressWarnings("deprecation")
	public MapReduceIterable<TDocument> mapReduce(String mapFunction, String reduceFunction) {
		return this.downstream.mapReduce(currentSession(), mapFunction, reduceFunction);
	}

	@SuppressWarnings("deprecation")
	public <TResult> MapReduceIterable<TResult> mapReduce(String mapFunction, String reduceFunction, Class<TResult> resultClass) {
		return this.downstream.mapReduce(currentSession(), mapFunction, reduceFunction, resultClass);
	}

	@SuppressWarnings("deprecation")
	public MapReduceIterable<TDocument> mapReduce(ClientSession clientSession, String mapFunction, String reduceFunction) {
		return this.downstream.mapReduce(clientSession, mapFunction, reduceFunction);
	}

	@SuppressWarnings("deprecation")
	public <TResult> MapReduceIterable<TResult> mapReduce(ClientSession clientSession, String mapFunction, String reduceFunction, Class<TResult> resultClass) {
		return this.downstream.mapReduce(clientSession, mapFunction, reduceFunction, resultClass);
	}

	public BulkWriteResult bulkWrite(List<? extends WriteModel<? extends TDocument>> requests) {
		return this.downstream.bulkWrite(currentSession(), requests);
	}

	public BulkWriteResult bulkWrite(List<? extends WriteModel<? extends TDocument>> requests, BulkWriteOptions options) {
		return this.downstream.bulkWrite(currentSession(), requests, options);
	}

	public BulkWriteResult bulkWrite(ClientSession clientSession, List<? extends WriteModel<? extends TDocument>> requests) {
		return this.downstream.bulkWrite(clientSession, requests);
	}

	public BulkWriteResult bulkWrite(ClientSession clientSession, List<? extends WriteModel<? extends TDocument>> requests, BulkWriteOptions options) {
		return this.downstream.bulkWrite(clientSession, requests, options);
	}

	public InsertOneResult insertOne(TDocument document) {
		return this.downstream.insertOne(currentSession(), document);
	}

	public InsertOneResult insertOne(TDocument document, InsertOneOptions options) {
		return this.downstream.insertOne(currentSession(), document, options);
	}

	public InsertOneResult insertOne(ClientSession clientSession, TDocument document) {
		return this.downstream.insertOne(clientSession, document);
	}

	public InsertOneResult insertOne(ClientSession clientSession, TDocument document, InsertOneOptions options) {
		return this.downstream.insertOne(clientSession, document, options);
	}

	public InsertManyResult insertMany(List<? extends TDocument> documents) {
		return this.downstream.insertMany(currentSession(), documents);
	}

	public InsertManyResult insertMany(List<? extends TDocument> documents, InsertManyOptions options) {
		return this.downstream.insertMany(currentSession(), documents, options);
	}

	public InsertManyResult insertMany(ClientSession clientSession, List<? extends TDocument> documents) {
		return this.downstream.insertMany(clientSession, documents);
	}

	public InsertManyResult insertMany(ClientSession clientSession, List<? extends TDocument> documents, InsertManyOptions options) {
		return this.downstream.insertMany(clientSession, documents, options);
	}

	public DeleteResult deleteOne(Bson filter) {
		return this.downstream.deleteOne(currentSession(), filter);
	}

	public DeleteResult deleteOne(Bson filter, DeleteOptions options) {
		return this.downstream.deleteOne(currentSession(), filter, options);
	}

	public DeleteResult deleteOne(ClientSession clientSession, Bson filter) {
		return this.downstream.deleteOne(clientSession, filter);
	}

	public DeleteResult deleteOne(ClientSession clientSession, Bson filter, DeleteOptions options) {
		return this.downstream.deleteOne(clientSession, filter, options);
	}

	public DeleteResult deleteMany(Bson filter) {
		return this.downstream.deleteMany(currentSession(), filter);
	}

	public DeleteResult deleteMany(Bson filter, DeleteOptions options) {
		return this.downstream.deleteMany(currentSession(), filter, options);
	}

	public DeleteResult deleteMany(ClientSession clientSession, Bson filter) {
		return this.downstream.deleteMany(clientSession, filter);
	}

	public DeleteResult deleteMany(ClientSession clientSession, Bson filter, DeleteOptions options) {
		return this.downstream.deleteMany(clientSession, filter, options);
	}

	public UpdateResult replaceOne(Bson filter, TDocument replacement) {
		return this.downstream.replaceOne(currentSession(), filter, replacement);
	}

	public UpdateResult replaceOne(Bson filter, TDocument replacement, ReplaceOptions replaceOptions) {
		return this.downstream.replaceOne(currentSession(), filter, replacement, replaceOptions);
	}

	public UpdateResult replaceOne(ClientSession clientSession, Bson filter, TDocument replacement) {
		return this.downstream.replaceOne(clientSession, filter, replacement);
	}

	public UpdateResult replaceOne(ClientSession clientSession, Bson filter, TDocument replacement, ReplaceOptions replaceOptions) {
		return this.downstream.replaceOne(clientSession, filter, replacement, replaceOptions);
	}

	public UpdateResult updateOne(Bson filter, Bson update) {
		return this.downstream.updateOne(currentSession(), filter, update);
	}

	public UpdateResult updateOne(Bson filter, Bson update, UpdateOptions updateOptions) {
		return this.downstream.updateOne(currentSession(), filter, update, updateOptions);
	}

	public UpdateResult updateOne(ClientSession clientSession, Bson filter, Bson update) {
		return this.downstream.updateOne(clientSession, filter, update);
	}

	public UpdateResult updateOne(ClientSession clientSession, Bson filter, Bson update, UpdateOptions updateOptions) {
		return this.downstream.updateOne(clientSession, filter, update, updateOptions);
	}

	public UpdateResult updateOne(Bson filter, List<? extends Bson> update) {
		return this.downstream.updateOne(currentSession(), filter, update);
	}

	public UpdateResult updateOne(Bson filter, List<? extends Bson> update, UpdateOptions updateOptions) {
		return this.downstream.updateOne(currentSession(), filter, update, updateOptions);
	}

	public UpdateResult updateOne(ClientSession clientSession, Bson filter, List<? extends Bson> update) {
		return this.downstream.updateOne(clientSession, filter, update);
	}

	public UpdateResult updateOne(ClientSession clientSession, Bson filter, List<? extends Bson> update, UpdateOptions updateOptions) {
		return this.downstream.updateOne(clientSession, filter, update, updateOptions);
	}

	public UpdateResult updateMany(Bson filter, Bson update) {
		return this.downstream.updateMany(currentSession(), filter, update);
	}

	public UpdateResult updateMany(Bson filter, Bson update, UpdateOptions updateOptions) {
		return this.downstream.updateMany(currentSession(), filter, update, updateOptions);
	}

	public UpdateResult updateMany(ClientSession clientSession, Bson filter, Bson update) {
		return this.downstream.updateMany(clientSession, filter, update);
	}

	public UpdateResult updateMany(ClientSession clientSession, Bson filter, Bson update, UpdateOptions updateOptions) {
		return this.downstream.updateMany(clientSession, filter, update, updateOptions);
	}

	public UpdateResult updateMany(Bson filter, List<? extends Bson> update) {
		return this.downstream.updateMany(currentSession(), filter, update);
	}

	public UpdateResult updateMany(Bson filter, List<? extends Bson> update, UpdateOptions updateOptions) {
		return this.downstream.updateMany(currentSession(), filter, update, updateOptions);
	}

	public UpdateResult updateMany(ClientSession clientSession, Bson filter, List<? extends Bson> update) {
		return this.downstream.updateMany(clientSession, filter, update);
	}

	public UpdateResult updateMany(ClientSession clientSession, Bson filter, List<? extends Bson> update, UpdateOptions updateOptions) {
		return this.downstream.updateMany(clientSession, filter, update, updateOptions);
	}

	public TDocument findOneAndDelete(Bson filter) {
		return this.downstream.findOneAndDelete(currentSession(), filter);
	}

	public TDocument findOneAndDelete(Bson filter, FindOneAndDeleteOptions options) {
		return this.downstream.findOneAndDelete(currentSession(), filter, options);
	}

	public TDocument findOneAndDelete(ClientSession clientSession, Bson filter) {
		return this.downstream.findOneAndDelete(clientSession, filter);
	}

	public TDocument findOneAndDelete(ClientSession clientSession, Bson filter, FindOneAndDeleteOptions options) {
		return this.downstream.findOneAndDelete(clientSession, filter, options);
	}

	public TDocument findOneAndReplace(Bson filter, TDocument replacement) {
		return this.downstream.findOneAndReplace(currentSession(), filter, replacement);
	}

	public TDocument findOneAndReplace(Bson filter, TDocument replacement, FindOneAndReplaceOptions options) {
		return this.downstream.findOneAndReplace(currentSession(), filter, replacement, options);
	}

	public TDocument findOneAndReplace(ClientSession clientSession, Bson filter, TDocument replacement) {
		return this.downstream.findOneAndReplace(clientSession, filter, replacement);
	}

	public TDocument findOneAndReplace(ClientSession clientSession, Bson filter, TDocument replacement, FindOneAndReplaceOptions options) {
		return this.downstream.findOneAndReplace(clientSession, filter, replacement, options);
	}

	public TDocument findOneAndUpdate(Bson filter, Bson update) {
		return this.downstream.findOneAndUpdate(currentSession(), filter, update);
	}

	public TDocument findOneAndUpdate(Bson filter, Bson update, FindOneAndUpdateOptions options) {
		return this.downstream.findOneAndUpdate(currentSession(), filter, update, options);
	}

	public TDocument findOneAndUpdate(ClientSession clientSession, Bson filter, Bson update) {
		return this.downstream.findOneAndUpdate(clientSession, filter, update);
	}

	public TDocument findOneAndUpdate(ClientSession clientSession, Bson filter, Bson update, FindOneAndUpdateOptions options) {
		return this.downstream.findOneAndUpdate(clientSession, filter, update, options);
	}

	public TDocument findOneAndUpdate(Bson filter, List<? extends Bson> update) {
		return this.downstream.findOneAndUpdate(currentSession(), filter, update);
	}

	public TDocument findOneAndUpdate(Bson filter, List<? extends Bson> update, FindOneAndUpdateOptions options) {
		return this.downstream.findOneAndUpdate(currentSession(), filter, update, options);
	}

	public TDocument findOneAndUpdate(ClientSession clientSession, Bson filter, List<? extends Bson> update) {
		return this.downstream.findOneAndUpdate(clientSession, filter, update);
	}

	public TDocument findOneAndUpdate(ClientSession clientSession, Bson filter, List<? extends Bson> update, FindOneAndUpdateOptions options) {
		return this.downstream.findOneAndUpdate(clientSession, filter, update, options);
	}

	public void drop() {
		this.downstream.drop(currentSession());
	}

	public void drop(ClientSession clientSession) {
		this.downstream.drop(clientSession);
	}

	@Override
	public void drop(DropCollectionOptions dropCollectionOptions) {
		this.downstream.drop(dropCollectionOptions);
	}

	@Override
	public void drop(ClientSession clientSession, DropCollectionOptions dropCollectionOptions) {
		this.downstream.drop(clientSession, dropCollectionOptions);
	}

	@Override
	public String createSearchIndex(String indexName, Bson definition) {
		return downstream.createSearchIndex(indexName, definition);
	}

	@Override
	public String createSearchIndex(Bson definition) {
		return downstream.createSearchIndex(definition);
	}

	@Override
	public List<String> createSearchIndexes(List<SearchIndexModel> searchIndexModels) {
		return downstream.createSearchIndexes(searchIndexModels);
	}

	@Override
	public void updateSearchIndex(String indexName, Bson definition) {
		downstream.updateSearchIndex(indexName, definition);
	}

	@Override
	public void dropSearchIndex(String indexName) {
		downstream.dropSearchIndex(indexName);
	}

	@Override
	public ListSearchIndexesIterable<Document> listSearchIndexes() {
		return downstream.listSearchIndexes();
	}

	@Override
	public <TResult> ListSearchIndexesIterable<TResult> listSearchIndexes(Class<TResult> tResultClass) {
		return downstream.listSearchIndexes(tResultClass);
	}

	public String createIndex(Bson keys) {
		return this.downstream.createIndex(currentSession(), keys);
	}

	public String createIndex(Bson keys, IndexOptions indexOptions) {
		return this.downstream.createIndex(currentSession(), keys, indexOptions);
	}

	public String createIndex(ClientSession clientSession, Bson keys) {
		return this.downstream.createIndex(clientSession, keys);
	}

	public String createIndex(ClientSession clientSession, Bson keys, IndexOptions indexOptions) {
		return this.downstream.createIndex(clientSession, keys, indexOptions);
	}

	public List<String> createIndexes(List<IndexModel> indexes) {
		return this.downstream.createIndexes(currentSession(), indexes);
	}

	public List<String> createIndexes(List<IndexModel> indexes, CreateIndexOptions createIndexOptions) {
		return this.downstream.createIndexes(currentSession(), indexes, createIndexOptions);
	}

	public List<String> createIndexes(ClientSession clientSession, List<IndexModel> indexes) {
		return this.downstream.createIndexes(clientSession, indexes);
	}

	public List<String> createIndexes(ClientSession clientSession, List<IndexModel> indexes, CreateIndexOptions createIndexOptions) {
		return this.downstream.createIndexes(clientSession, indexes, createIndexOptions);
	}

	public ListIndexesIterable<Document> listIndexes() {
		return this.downstream.listIndexes(currentSession());
	}

	public <TResult> ListIndexesIterable<TResult> listIndexes(Class<TResult> resultClass) {
		return this.downstream.listIndexes(currentSession(), resultClass);
	}

	public ListIndexesIterable<Document> listIndexes(ClientSession clientSession) {
		return this.downstream.listIndexes(clientSession);
	}

	public <TResult> ListIndexesIterable<TResult> listIndexes(ClientSession clientSession, Class<TResult> resultClass) {
		return this.downstream.listIndexes(clientSession, resultClass);
	}

	public void dropIndex(String indexName) {
		this.downstream.dropIndex(currentSession(), indexName);
	}

	public void dropIndex(String indexName, DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndex(currentSession(), indexName, dropIndexOptions);
	}

	public void dropIndex(Bson keys) {
		this.downstream.dropIndex(currentSession(), keys);
	}

	public void dropIndex(Bson keys, DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndex(currentSession(), keys, dropIndexOptions);
	}

	public void dropIndex(ClientSession clientSession, String indexName) {
		this.downstream.dropIndex(clientSession, indexName);
	}

	public void dropIndex(ClientSession clientSession, Bson keys) {
		this.downstream.dropIndex(clientSession, keys);
	}

	public void dropIndex(ClientSession clientSession, String indexName, DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndex(clientSession, indexName, dropIndexOptions);
	}

	public void dropIndex(ClientSession clientSession, Bson keys, DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndex(clientSession, keys, dropIndexOptions);
	}

	public void dropIndexes() {
		this.downstream.dropIndexes(currentSession());
	}

	public void dropIndexes(ClientSession clientSession) {
		this.downstream.dropIndexes(clientSession);
	}

	public void dropIndexes(DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndexes(currentSession(), dropIndexOptions);
	}

	public void dropIndexes(ClientSession clientSession, DropIndexOptions dropIndexOptions) {
		this.downstream.dropIndexes(clientSession, dropIndexOptions);
	}

	public void renameCollection(MongoNamespace newCollectionNamespace) {
		this.downstream.renameCollection(currentSession(), newCollectionNamespace);
	}

	public void renameCollection(MongoNamespace newCollectionNamespace, RenameCollectionOptions renameCollectionOptions) {
		this.downstream.renameCollection(currentSession(), newCollectionNamespace, renameCollectionOptions);
	}

	public void renameCollection(ClientSession clientSession, MongoNamespace newCollectionNamespace) {
		this.downstream.renameCollection(clientSession, newCollectionNamespace);
	}

	public void renameCollection(ClientSession clientSession, MongoNamespace newCollectionNamespace, RenameCollectionOptions renameCollectionOptions) {
		this.downstream.renameCollection(clientSession, newCollectionNamespace, renameCollectionOptions);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalCollection.class);
}
