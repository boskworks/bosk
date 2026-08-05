package works.bosk.drivers.mongo.internal;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.libtesting.BlockingGate;

/**
 * Wraps a {@link MongoClient} so a test can deterministically interpose on
 * database operations, either by blocking a matching collection method call or
 * by pausing the cursor of a matching find.
 * <p>
 * The wrapping is transitive through the client, database, collection,
 * iterable, and cursor chain, so a test never sees an unwrapped element and can
 * rely on its interpositions applying no matter which wrapper the driver holds
 * at the time.
 * <p>
 * Interpositions are one-shot: once a {@link BlockingGate} has been released,
 * later matching calls proceed without pausing, which matters when the driver
 * retries an aborted transaction.
 */
public final class InterceptingMongoClient {
	private final MongoClient real;
	private final List<CollectionMethodBlock> collectionMethodBlocks = new ArrayList<>();
	private final List<FindCursorPause> findCursorPauses = new ArrayList<>();

	private InterceptingMongoClient(MongoClient real) {
		this.real = real;
	}

	public static InterceptingMongoClient wrapping(MongoClient real) {
		return new InterceptingMongoClient(real);
	}

	/**
	 * Returns the wrapped client, for use where a {@link MongoClient} is required.
	 * Interpositions may be registered before or after this is called.
	 */
	public MongoClient client() {
		return wrappedClient();
	}

	/**
	 * Blocks calls to the named collection method whose last {@link Bson}
	 * argument matches {@code filterMatches}: the call signals {@code gate} and
	 * then waits for the test to release it before delegating to the real
	 * collection.
	 */
	public InterceptingMongoClient blockCollectionMethod(String methodName, Predicate<Bson> filterMatches, BlockingGate gate) {
		collectionMethodBlocks.add(new CollectionMethodBlock(methodName, filterMatches, gate));
		return this;
	}

	/**
	 * Pauses the cursor of a find whose filter matches {@code filterMatches},
	 * after it has read {@code afterDocuments} documents: the cursor signals
	 * {@code gate} and then waits for the test to release it before delivering
	 * the document. The find is read one document at a time so the pause can
	 * straddle a document boundary.
	 */
	public InterceptingMongoClient pauseFindCursor(Predicate<Bson> filterMatches, int afterDocuments, BlockingGate gate) {
		findCursorPauses.add(new FindCursorPause(filterMatches, afterDocuments, gate));
		return this;
	}

	private MongoClient wrappedClient() {
		return proxy(MongoClient.class, real, (proxy, method, args) -> {
			Object result = invoke(method, real, args);
			if (method.getName().equals("getDatabase")) {
				return wrappedDatabase((MongoDatabase) result);
			}
			return result;
		});
	}

	private MongoDatabase wrappedDatabase(MongoDatabase real) {
		return proxy(MongoDatabase.class, real, (proxy, method, args) -> {
			Object result = invoke(method, real, args);
			if (method.getName().equals("getCollection")) {
				@SuppressWarnings("unchecked")
				MongoCollection<BsonDocument> realCollection = (MongoCollection<BsonDocument>) result;
				return wrappedCollection(realCollection);
			}
			return result;
		});
	}

	private MongoCollection<BsonDocument> wrappedCollection(MongoCollection<BsonDocument> real) {
		return proxy(MongoCollection.class, real, (proxy, method, args) -> {
			String name = method.getName();
			if (name.equals("find")) {
				Bson filter = lastBsonArg(args);
				if (filter != null) {
					FindCursorPause pause = matchingPause(filter);
					if (pause != null) {
						@SuppressWarnings("unchecked")
						FindIterable<BsonDocument> realIterable = (FindIterable<BsonDocument>) invoke(method, real, args);
						return pausedFind(realIterable.batchSize(1), pause);
					}
				}
			} else {
				CollectionMethodBlock block = matchingBlock(name, args);
				if (block != null) {
					LOGGER.debug("Blocking {}; waiting for the test to release", name);
					block.gate().signal();
					block.gate().awaitRelease(RELEASE_TIMEOUT);
				}
			}
			Object result = invoke(method, real, args);
			if (CHAINED_COLLECTION_METHODS.contains(name)) {
				@SuppressWarnings("unchecked")
				MongoCollection<BsonDocument> chained = (MongoCollection<BsonDocument>) result;
				return wrappedCollection(chained);
			}
			return result;
		});
	}

	private FindIterable<BsonDocument> pausedFind(FindIterable<BsonDocument> real, FindCursorPause pause) {
		return proxy(FindIterable.class, real, (proxy, method, args) -> {
			String name = method.getName();
			Object result = invoke(method, real, args);
			if (name.equals("cursor")) {
				@SuppressWarnings("unchecked")
				MongoCursor<BsonDocument> realCursor = (MongoCursor<BsonDocument>) result;
				return pausingCursor(realCursor, pause);
			} else if (CHAINED_ITERABLE_METHODS.contains(name)) {
				@SuppressWarnings("unchecked")
				FindIterable<BsonDocument> chained = (FindIterable<BsonDocument>) result;
				return pausedFind(chained, pause);
			}
			return result;
		});
	}

	private MongoCursor<BsonDocument> pausingCursor(MongoCursor<BsonDocument> real, FindCursorPause pause) {
		AtomicInteger documentsRead = new AtomicInteger();
		return proxy(MongoCursor.class, real, (proxy, method, args) -> {
			if (method.getName().equals("next")) {
				BsonDocument doc = real.next();
				if (documentsRead.incrementAndGet() == pause.afterDocuments()) {
					LOGGER.debug("Pausing the cursor of a find; waiting for the test to write");
					pause.gate().signal();
					pause.gate().awaitRelease(RELEASE_TIMEOUT);
				}
				return doc;
			}
			return invoke(method, real, args);
		});
	}

	private FindCursorPause matchingPause(Bson filter) {
		return findCursorPauses.stream()
			.filter(pause -> pause.filterMatches().test(filter))
			.findFirst()
			.orElse(null);
	}

	private CollectionMethodBlock matchingBlock(String methodName, Object[] args) {
		Bson filter = lastBsonArg(args);
		return collectionMethodBlocks.stream()
			.filter(block -> block.methodName().equals(methodName))
			.filter(block -> block.filterMatches().test(filter))
			.findFirst()
			.orElse(null);
	}

	private static Bson lastBsonArg(Object[] args) {
		if (args == null) {
			return null;
		}
		for (int i = args.length - 1; i >= 0; i--) {
			if (args[i] instanceof Bson bson) {
				return bson;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> iface, Object target, InvocationHandler handler) {
		return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
	}

	private static Object invoke(Method method, Object target, Object... args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	private record CollectionMethodBlock(String methodName, Predicate<Bson> filterMatches, BlockingGate gate) {
	}

	private record FindCursorPause(Predicate<Bson> filterMatches, int afterDocuments, BlockingGate gate) {
	}

	private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(60);
	private static final Set<String> CHAINED_COLLECTION_METHODS = Set.of(
		"withReadConcern", "withReadPreference", "withWriteConcern", "withCodecRegistry", "withDocumentClass", "withTimeout");
	private static final Set<String> CHAINED_ITERABLE_METHODS = Set.of(
		"filter", "limit", "skip", "sort", "projection", "batchSize");
	private static final Logger LOGGER = LoggerFactory.getLogger(InterceptingMongoClient.class);
}
