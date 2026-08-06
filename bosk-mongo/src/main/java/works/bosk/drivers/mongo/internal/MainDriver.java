package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoClientSettings.Builder;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.Identifier;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode;
import works.bosk.drivers.mongo.MongoDriverSettings.SequoiaFormat;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.drivers.mongo.exceptions.InitialStateFailureException;
import works.bosk.drivers.mongo.status.MongoStatus;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.logging.MappedDiagnosticContext.MDCScope;

import static com.mongodb.MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL;
import static com.mongodb.client.model.Sorts.ascending;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.DISCONNECT;
import static works.bosk.drivers.mongo.internal.Formatter.REVISION_ZERO;
import static works.bosk.logging.MappedDiagnosticContext.setupMDC;

/**
 * This is the driver returned to the user by {@link MongoDriver#factory}.
 * This class implements the fault tolerance framework in cooperation with {@link ChangeReceiver}.
 * It's mostly exception handling code and diagnostics.
 * The actual database interactions used to implement the {@link BoskDriver} methods,
 * as well as most interactions with the downstream driver,
 * are delegated to a {@link FormatDriver} object that can be swapped out dynamically
 * as the database evolves.
 */
public final class MainDriver<R extends StateTreeNode> implements MongoDriver {
	private final BoskInfo<R> boskInfo;
	private final ChangeReceiver receiver;
	private final MongoDriverSettings driverSettings;
	private final BsonSerializer bsonSerializer;
	private final BoskDriver downstream;
	private final Deque<Closeable> closeables = new ArrayDeque<>();
	private final TransactionalCollection queryCollection;
	private final Listener listener;
	final Formatter formatter;

	final long flushTimeout;
	final long initializeTimeout;
	final long reinitializationTimeout;

	/**
	 * {@link MongoClient#close()} throws if called more than once.
	 * {@link MongoDriver} is more civilized: subsequent calls do nothing.
	 * Hence, we must keep track of whether we've already closed the {@link MongoClient}.
	 */
	private final AtomicBoolean isClosed = new AtomicBoolean(false);

	/**
	 * Hold this while waiting on {@link #formatDriverChanged}
	 */
	private final ReentrantLock formatDriverLock = new ReentrantLock();

	/**
	 * Wait on this in cases where the {@link #formatDriver} isn't working
	 * and might be asynchronously repaired.
	 */
	private final Condition formatDriverChanged = formatDriverLock.newCondition();

	private volatile FormatDriver<R> formatDriver = new DisconnectedDriver<>(new Exception("Driver not yet initialized"));

	/**
	 * Allows tests to install test-only hooks controlling the driver's internals.
	 * <p>
	 * This works because {@code MainDriver} is instantiated
	 * on the same thread as the {@code Bosk}: the hooks are read here and
	 * captured at construction, so they apply to every thread that later
	 * does database work. See {@link TestHooks}.
	 */
	static final ThreadLocal<TestHooks> TEST_HOOKS = ThreadLocal.withInitial(TestHooks::noop);

	record MongoClientFactory(
		Function<MongoClientSettings, MongoClient> function,
		boolean shouldClose
	) {
		static final MongoClientFactory ALWAYS_CREATE = new MongoClientFactory(MongoClients::create, true);
	}

	/**
	 * Records the value of {@link #TEST_HOOKS} as it was
	 * at the time of the constructor call.
	 */
	final TestHooks testHooks = TEST_HOOKS.get();

	public MainDriver(
		BoskInfo<R> boskInfo,
		MongoClientSettings clientSettings,
		MongoDriverSettings driverSettings,
		BsonSerializer bsonSerializer,
		BoskDriver downstream
	) {
		try (MDCScope _ = setupMDC(boskInfo.name(), boskInfo.instanceID())) {
			this.boskInfo = boskInfo;
			this.driverSettings = driverSettings;
			this.bsonSerializer = bsonSerializer;
			this.downstream = downstream;

			// Flushes work by waiting for the latest version to arrive on the change stream.
			// If we wait for two heartbeats and don't see the update, something has gone wrong.
			//
			// (Note that flush does a retry, so it will actually take
			// twice as long as this before throwing a FlushFailureException.)
			flushTimeout = 2L * driverSettings.timescaleMS();

			// Initialization must wait for a heartbeat to succeed, so we wait twice that long,
			// plus one more for the network connection and initial query.
			initializeTimeout = 3L * driverSettings.timescaleMS();

			// The sum of the steps required to reinitialize after a disconnect,
			// plus a little extra to make sure we don't cut it off when it's about to succeed.
			reinitializationTimeout =
				2L * driverSettings.timescaleMS() // ChangeStream reconnect
					+ initializeTimeout // Initialize after reconnecting
					+ driverSettings.timescaleMS() // Extra buffer
			;

			Builder commonSettingsBuilder = MongoClientSettings
				.builder(clientSettings)
				.applyToServerSettings(s ->
					// If timescaleMS is shorter than the default min heartbeat,
					// then we need to reduce this setting to prevent the client
					// from using a stale view of the server state for too long.
					// If timescaleMS is longer, then the user has told us
					// they don't mind longer delays and want the increased
					// efficiency of fewer heartbeats.
					// Either way, timescaleMS is the right value for this setting.
					//
					// Note that this doesn't set the heartbeat frequency itself.
					// That is left at the default value, since it is only used
					// to "notice" connectivity problems when the driver is quiescent,
					// which is not time-critical and is not governed by timescaleMS:
					// the actual behaviour of the bosk during a network partition
					// is that its contents remain fixed, and it doesn't matter much
					// whether that is achieved by formally disconnecting or simply
					// by doing nothing.
					s.minHeartbeatFrequency(driverSettings.timescaleMS(), MILLISECONDS))
				;

			// By default, we deal only with durable data that won't get rolled back.
			// In some circumstances, we need the very latest possible data for correctness,
			// so we override the ReadConcern in those cases.
			commonSettingsBuilder
				.readConcern(ReadConcern.MAJORITY)
				.writeConcern(WriteConcern.MAJORITY);

			var changeStreamSettingsBuilder = MongoClientSettings.builder(commonSettingsBuilder.build())
				.applyToClusterSettings(c ->
					c.serverSelectionTimeout(initializeTimeout, MILLISECONDS))
				.applyToSocketSettings(s ->
					s.connectTimeout(initializeTimeout, MILLISECONDS)
						// No read timeout for change streams; they can be idle indefinitely
						.readTimeout(0, MILLISECONDS))
				;

			var clientFactory = testHooks.clientFactory();

			var changeStreamClient = clientFactory.function.apply(changeStreamSettingsBuilder.build());
			if (clientFactory.shouldClose) {
				closeables.addFirst(changeStreamClient);
			}

			// Override timeouts to make them compatible with driverSettings.timescaleMS()
			var querySettingsBuilder = MongoClientSettings.builder(commonSettingsBuilder.build());
			querySettingsBuilder
				.timeout(flushTimeout, MILLISECONDS);

			var queryClient = clientFactory.function.apply(querySettingsBuilder.build());
			if (clientFactory.shouldClose) {
				closeables.addFirst(queryClient);
			}

			this.queryCollection = TransactionalCollection.of(queryClient
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME, BsonDocument.class), queryClient, testHooks.findInterceptor());
			LOGGER.debug("Using database \"{}\" collection \"{}\"", driverSettings.database(), COLLECTION_NAME);

			this.formatter = new Formatter(boskInfo, bsonSerializer);


			// Build the receiver
			Class<R> rootType = boskInfo.rootReference().targetClass();
			ChangeListener listener = this.listener = new Listener(new RemoteCallable<>(
				attrs -> doInitialState(rootType, attrs)));
			var factory = testHooks.listenerFactory();
			if (factory != null) {
				listener = factory.apply(listener);
			}
			this.receiver = new ChangeReceiver(
				boskInfo.name(),
				boskInfo.instanceID(),
				listener,
				driverSettings,
				changeStreamClient
					.getDatabase(driverSettings.database())
					.getCollection(COLLECTION_NAME, BsonDocument.class)
			);
		}

	}

	@Override
	public <RR extends StateTreeNode> RR initialState(Class<RR> rootType) throws InvalidTypeException, InterruptedException, IOException {
		try (var _ = beginDriverOperation("initialState({})", rootType)) {
			// The actual loading of the initial state happens on the ChangeReceiver thread.
			// Here, we just wait for that to finish and deal with the consequences.
			var task = listener.initialStateTask;
			if (task == null) {
				throw new IllegalStateException("initialState has already run");
			}
			try {
				return rootType.cast(task.call(boskInfo.context().getAttributes()));
			} catch (ExecutionException e) {
				switch (e.getCause()) {
					case InitialStateFailureException i -> {
						if (i.getCause() instanceof DownstreamInitialStateException d) {
							switch (d.getCause()) {
								case IOException io -> throw io;
								case InvalidTypeException ite -> throw ite;
								case InterruptedException ie -> throw ie;
								case RuntimeException r -> throw r;
								case null, default -> throw i;
							}
						} else {
							throw i;
						}
					}
					case null, default -> throw new AssertionError("Exception from initialState was not wrapped in InitialStateFailureException: " + e.getClass().getSimpleName(), e);
				}
			} finally {
				LOGGER.debug("Done initialState");
			}
		}
	}

	/**
	 * Called on the {@link ChangeReceiver}'s background thread via {@link Listener#initialStateTask}
	 * because it's important that this logic finishes before processing any change events,
	 * and no other change events can arrive concurrently.
	 *
	 * @param diagnosticAttributes the attributes from the {@link #initialState(Class) initialState} call
	 * @throws DatabaseLoadException if unable to load the initial state from the database
	 * @throws DownstreamInitialStateException if we attempt to delegate {@link #initialState} to
	 * the {@link #downstream} driver and it throws an exception
	 */
	private R doInitialState(Class<R> rootType, MapValue<String> diagnosticAttributes) throws InitialStateException {
		// This establishes a safe fallback in case things go wrong. It also causes any
		// calls to driver update methods to wait until we're finished here. (There shouldn't
		// be any such calls while initialState is still running, but this ensures that if any
		// do happen, they won't corrupt our internal state in confusing ways.)
		setDisconnectedDriver(FAILURE_TO_COMPUTE_INITIAL_STATE);

		// In effect, at this point, the entire driver is now single-threaded for the remainder
		// of this method. Our only concurrency concerns now involve database operations performed
		// by other processes.

		R entireState;
		try (var _ = queryCollection.newReadOnlySession()){
			// The load must read a consistent snapshot, so run it inside a
			// read-only transaction. (Refurbish runs its load inside its own
			// transaction, so the load is always transactional.)
			queryCollection.ensureTransactionStarted();
			FormatDriver<R> detectedDriver = detectFormat();
			StateAndMetadata<R> loadedState = detectedDriver.loadAllState();
			entireState = loadedState.state();

			// Hasn't technically been applied, but we're still initializing the Bosk, and its constructor won't return until the state has been applied
			detectedDriver.onHasBeenApplied(loadedState);

			publishFormatDriver(detectedDriver);
		} catch (UninitializedCollectionException e) {
			// We log this at warn because, in production, this is a big deal.
			// Annoying in tests, so we log it with UNINITIALIZED_COLLECTION_LOGGER so we can selectively disable it.
			UNINITIALIZED_COLLECTION_LOGGER.warn("Database collection is uninitialized; initializing now. ({})", e.getMessage());
			entireState = callDownstreamInitialState(rootType);
			try (
				var session = queryCollection.newSession()
			) {
				FormatDriver<R> preferredDriver = newPreferredFormatDriver();
				StateAndMetadata<R> priorContents = new StateAndMetadata<>(entireState, Optional.empty(), REVISION_ZERO, diagnosticAttributes);
				preferredDriver.initializeCollection(priorContents);
				session.commitTransactionIfAny();
				// We can now publish the driver knowing that the transaction, if there is one, has committed
				publishFormatDriver(preferredDriver);
			} catch (RuntimeException | FailedMongoClientSessionException e2) {
				LOGGER.warn("Failed to initialize database; disconnecting", e2);
				setDisconnectedDriver(e2);
			}
		} catch (UnrecognizedFormatException | InvalidCollectionContentsException | IOException | FailedMongoClientSessionException e) {
			throw new DatabaseLoadException("Unable to load initial state from MongoDB", e);
		}
		return entireState;
	}

	/**
	 * @throws DownstreamInitialStateException only
	 */
	private R callDownstreamInitialState(Class<R> rootType) throws DownstreamInitialStateException {
		try {
			return downstream.initialState(rootType);
		} catch (RuntimeException | Error | InvalidTypeException | IOException | InterruptedException e) {
			LOGGER.error("Downstream driver failed to compute initial state", e);
			throw new DownstreamInitialStateException("Fatal error: downstream driver failed to compute initial state", e);
		}
	}

	/**
	 * Refurbish is the one operation that always <em>must</em> happen in a transaction,
	 * or else we could fail after deleting the existing contents but before rewriting them,
	 * which would be catastrophic.
	 * <p>
	 * <em>Design note:</em> This operation shouldn't do any special coordination with
	 * the receiver/listener system, because other replicas won't.
	 * That system needs to cope with refurbish operations without any help.
	 */
	private void refurbishTransaction() throws IOException {
		queryCollection.ensureTransactionStarted();
		LOGGER.debug("Refurbishing to {}", driverSettings.preferredDatabaseFormat());
		try {
			FormatDriver<R> newFormatDriver;

			// The calls to loadAllState and initializeCollection must occur atomically
			// with respect to event processing, because both of them have side effects
			// that affect event processing (field tracking and flush locks, respectively).
			synchronized (receiver) {
				StateAndMetadata<R> allState = formatDriver.loadAllState();
				newFormatDriver = newPreferredFormatDriver();

				// initializeCollection is required to replace the manifest anyway,
				// so deleting it has no value; and if we do delete it, then every
				// FormatDriver must cope with deletions of the manifest document
				// to avoid disconnection during refurbish operations,
				// which is a burden. Let's just not.
				BsonDocument deletionFilter = new BsonDocument("_id", new BsonDocument("$ne", MANIFEST_ID));
				LOGGER.trace("Deleting state documents: {}", deletionFilter);
				testHooks.beforeRefurbishDelete().run();
				queryCollection.deleteMany(deletionFilter);

				newFormatDriver.initializeCollection(allState);
			}

			// We must rudely commit the transaction here, since correctness requires that
			// the database updates commit before we publish newFormatDriver.
			queryCollection.commitTransactionIfAny();

			publishFormatDriver(newFormatDriver);

			// Refurbish doesn't actually change what state has been applied to the bosk.
			// No need to do any downstream submit/flush, nor call onHasBeenApplied.
		} catch (InvalidCollectionContentsException e) {
			throw new IOException("Unable to refurbish database collection with invalid contents", e);
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		doRetryableDriverOperation(()->{
			bsonSerializer.initializeAllEnclosingPolyfills(target, formatDriver);
			formatDriver.submitReplacement(target, newValue);
		}, "submitReplacement({})", target);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		doRetryableDriverOperation(()->{
			bsonSerializer.initializeAllEnclosingPolyfills(target, formatDriver);
			formatDriver.submitConditionalReplacement(target, newValue, precondition, requiredValue);
		}, "submitConditionalReplacement({}, {}={})", target, precondition, requiredValue);
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		doRetryableDriverOperation(()->{
			bsonSerializer.initializeAllEnclosingPolyfills(target, formatDriver);
			formatDriver.submitConditionalCreation(target, newValue);
		}, "submitConditionalCreation({})", target);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		doRetryableDriverOperation(()->{
			bsonSerializer.initializeAllEnclosingPolyfills(target, formatDriver);
			formatDriver.submitDeletion(target);
		}, "submitDeletion({})", target);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		doRetryableDriverOperation(() -> {
			bsonSerializer.initializeAllEnclosingPolyfills(target, formatDriver);
			formatDriver.submitConditionalDeletion(target, precondition, requiredValue);
		}, "submitConditionalDeletion({}, {}={})", target, precondition, requiredValue);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		try {
			// TODO: This could use a read-only session, but doRetryableDriverOperation uses a normal read-write session
			//  because it was designed for updates rather than flushes.
			this.<InterruptedException, IOException>doRetryableDriverOperation(() -> {
				formatDriver.flush();
			}, "flush");
		} catch (RuntimeException e) {
			throw new FlushFailureException(e);
		}
	}

	@Override
	public void refurbish() throws IOException {
		doRetryableDriverOperation(() -> {
			refurbishTransaction();
		}, "refurbish");
	}

	@Override
	public MongoStatus readStatus() throws Exception {
		try (
			var _ = queryCollection.newReadOnlySession()
		) {
			MongoStatus partialResult = detectFormat().readStatus();
			Manifest manifest = loadManifest().manifest(); // TODO: Avoid loading the manifest again
			return partialResult.with(driverSettings.preferredDatabaseFormat(), manifest);
		}
	}

	@Override
	public void close() {
		receiver.close();
		formatDriver.close();
		var suppressedExceptions = new ArrayList<IOException>();
		if (!isClosed.getAndSet(true)) {
			// It's important we don't call these twice, or else they will throw
			closeables.forEach(closeable -> {
				try {
					closeable.close();
				} catch (IOException e) {
					suppressedExceptions.add(e);
				}
			});
		}
		if (!suppressedExceptions.isEmpty()) {
			var e = new IllegalStateException("Exceptions occurred while closing MainDriver");
			suppressedExceptions.forEach(e::addSuppressed);
			throw e;
		}
	}

	/**
	 * Contains logic that runs on the {@link ChangeReceiver}'s background thread
	 * to update {@link MainDriver}'s state in response to various occurrences.
	 */
	private class Listener implements ChangeListener {
		final RemoteCallable<MapValue<String>, R, InitialStateException> initialStateTask;

		private Listener(RemoteCallable<MapValue<String>, R, InitialStateException> initialStateTask) {
			this.initialStateTask = initialStateTask;
		}

		@Override
		public void onConnectionSucceeded() throws
			UnrecognizedFormatException,
			FailedMongoClientSessionException,
			InterruptedException,
			IOException,
			InitialStateException,
			InvalidCollectionContentsException
		{
			LOGGER.debug("onConnectionSucceeded");
			if (initialStateTask.isDone()) {
				FormatDriver<R> newDriver;
				StateAndMetadata<R> allState;
				try (var _ = queryCollection.newReadOnlySession()) {
					LOGGER.debug("Loading database state to submit to downstream driver");
					// The load must read a consistent snapshot, so run it inside a
					// read-only transaction. (Refurbish runs its load inside its own
					// transaction, so the load is always transactional.)
					queryCollection.ensureTransactionStarted();
					newDriver = detectFormat();
					allState = newDriver.loadAllState();
					LOGGER.trace("Loaded state: {}", allState);
				} catch (UninitializedCollectionException e) {
					// We don't auto-initialize after the initialStateTask is done
					throw new DatabaseLoadException("Cannot reload state from database", e);
				}

				// Note: can't call downstream methods with a session open,
				// because that could run hooks, which could themselves submit
				// new updates, and those updates need their own session.

				// Update the FormatDriver before submitting the new state downstream in case
				// a hook is triggered that calls more driver methods.
				// Note: that there's no risk that another thread will submit a downstream update "out of order"
				// before ours (below) because this code runs on the ChangeReceiver thread, which is
				// the only thread that submits updates downstream.

				publishFormatDriver(newDriver);

				// TODO: It's not clear we actually want loadedState.diagnosticAttributes here.
				// This causes downstream.submitReplacement to be associated with the last update to the state,
				// which is of dubious relevance. We might just want to use the context from the current thread,
				// which is probably empty because this runs on the ChangeReceiver thread.
				try (
					var _ = boskInfo.context().withOnly(allState.diagnosticAttributes())
				) {
					downstream.submitReplacement(boskInfo.rootReference(), allState.state());
					LOGGER.debug("Done submitting downstream");
				}

				downstream.flush();
				newDriver.onHasBeenApplied(allState);
			} else {
				LOGGER.debug("Running initialStateTask");
				try {
					initialStateTask.run();
				} catch (InitialStateException | RuntimeException e) {
					handleInitFailure(e);
					if (driverSettings.initialDatabaseUnavailableMode() == DISCONNECT) {
						throw new DatabaseLoadException("Unable to load initial state from MongoDB", e);
					}
				}
			}
		}

		@Override
		public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
			LOGGER.debug("onEvent({}:{})", event.getOperationType().getValue(), getDocumentKeyValue(event));
			LOGGER.trace("Event details: {}", event);
			formatDriver.onEvent(event);
		}

		private Object getDocumentKeyValue(ChangeStreamDocument<BsonDocument> event) {
			BsonDocument documentKey = event.getDocumentKey();
			if (documentKey == null) {
				return null;
			} else  {
				BsonValue value = documentKey.get("_id");
				if (value instanceof BsonString b) {
					return b.getValue();
				} else {
					return value;
				}
			}
		}

		@Override
		public void onConnectionFailed(Exception cause) throws DownstreamInitialStateException {
			LOGGER.debug("onConnectionFailed");
			// If there's an initialStateAction, the main thread is waiting for us.
			// Signal the failure to the waiting main thread.
			if (initialStateTask.isDone()) {
				LOGGER.debug("Nothing to do");
			} else {
				handleInitFailure(cause);
			}
		}

		/**
		 * Resolves a failure to load the initial state from the database, from either
		 * {@link #onConnectionSucceeded} (the change stream cursor opened but reading the
		 * state failed) or {@link ChangeListener#onConnectionFailed} (the cursor could not open).
		 * Both converge here so the {@link InitialDatabaseUnavailableMode mode} decision
		 * lives in exactly one place.
		 *
		 * @param cause why we were unable to load the initial state from the database
		 * @throws DownstreamInitialStateException if DISCONNECT mode was used and the
		 * downstream driver also failed to provide initial state
		 */
		private void handleInitFailure(Exception cause) throws DownstreamInitialStateException {
			switch (driverSettings.initialDatabaseUnavailableMode()) {
				case FAIL_FAST:
					LOGGER.debug("Unable to load initial state from database; aborting initialization", cause);
					initialStateTask.fail(new InitialStateFailureException("Unable to load initial state from MongoDB", cause));
					break;
				case DISCONNECT:
					LOGGER.info("Unable to load initial state from database; will proceed with downstream.initialState", cause);
					setDisconnectedDriver(cause);
					try {
						initialStateTask.complete(callDownstreamInitialState(boskInfo.rootReference().targetClass()));
					} catch (DownstreamInitialStateException e) {
						// The main thread gets an InitialStateFailureException
						var mainThreadException = new InitialStateFailureException("Unable to obtain initial state from MongoDB or downstream driver", e);
						mainThreadException.addSuppressed(cause);
						initialStateTask.fail(mainThreadException);

						// The ChangeReceiver thread gets the original DownstreamInitialStateException
						throw e;
					}
					break;
			}
		}

		@Override
		public void onDisconnect(Throwable e) {
			LOGGER.debug("onDisconnect({})", e.toString());
			formatDriver.close();
			setDisconnectedDriver(e);
		}
	}

	private FormatDriver<R> newPreferredFormatDriver() {
		return newFormatDriver(driverSettings.preferredDatabaseFormat());
	}

	private FormatDriver<R> detectFormat() throws UninitializedCollectionException, UnrecognizedFormatException {
		LOGGER.debug("Detecting format");
		Manifest manifest = loadManifest().manifest();
		DatabaseFormat format = manifest.pando().isPresent()? manifest.pando().get() : SEQUOIA;
		return newFormatDriver(format);
	}

	record ManifestInfo(Manifest manifest, @Nullable BsonString manifestId) {}

	private ManifestInfo loadManifest() throws UnrecognizedFormatException, UninitializedCollectionException {
		// The manifest ID is deliberately chosen to sort first by ID.
		// This way we can distinguish three cases in one query:
		// 1) No documents at all: the collection is uninitialized.
		// 2) A manifest document exists: the collection is initialized and we can proceed.
		// 3) No manifest document, but another document exists: the collection contents are invalid.
		try (DocCursor cursor = queryCollection
			.find(new BsonDocument())
			.sort(ascending("_id"))
			.limit(1)
			.cursor()
		) {
			if (cursor.hasNext()) {
				BsonDocument doc = cursor.next();
				if (MANIFEST_ID.equals(doc.getString("_id"))) {
					return new ManifestInfo(formatter.decodeManifest(doc), doc.getString("_id"));
				} else {
					throw new UnrecognizedFormatException("Manifest document not found: "
						+ "collection=" + driverSettings.database()
						+ "." + COLLECTION_NAME
						+ " _id=" + MANIFEST_ID
						+ "; found \"" + doc.getString("_id") + "\"");
				}
			} else {
				throw new UninitializedCollectionException(
					"Collection is empty: " + driverSettings.database()
						+ "." + COLLECTION_NAME
				);
			}
		}
	}

	/**
	 * Meant for tests
	 */
	ManifestInfo loadManifestInfo() throws UnrecognizedFormatException, FailedMongoClientSessionException, UninitializedCollectionException {
		try (var _ = queryCollection.newReadOnlySession()) {
			return loadManifest();
		}
	}

	private FormatDriver<R> newFormatDriver(DatabaseFormat format) {
		return switch (format) {
			case SequoiaFormat _ -> new SequoiaFormatDriver<>(
				boskInfo,
				queryCollection,
				driverSettings,
				bsonSerializer,
				flushTimeout,
				downstream
			);
			case PandoFormat pandoFormat -> new PandoFormatDriver<>(
				boskInfo,
				queryCollection,
				driverSettings,
				pandoFormat,
				bsonSerializer,
				flushTimeout,
				downstream);
		};
	}


	private MDCScope beginDriverOperation(String description, Object... args) {
		if (isClosed.get()) {
			throw new IllegalStateException("Driver is closed");
		}
		MDCScope ex = setupMDC(boskInfo.name(), boskInfo.instanceID());
		if (LOGGER.isDebugEnabled()) {
			Object[] argsWithContext = new Object[args.length + 2];
			System.arraycopy(args, 0, argsWithContext, 0, args.length);
			argsWithContext[args.length] = boskInfo.name();
			LOGGER.debug(description + " w/{}@{}", argsWithContext);
		}
		if (driverSettings.testing().eventDelayMS() < 0) {
			LOGGER.debug("| eventDelayMS {}ms ", driverSettings.testing().eventDelayMS());
			try {
				Thread.sleep(-driverSettings.testing().eventDelayMS());
			} catch (InterruptedException e) {
				LOGGER.debug("Sleep interrupted", e);
			}
		}
		return ex;
	}

	private <X extends Exception, Y extends Exception> void doRetryableDriverOperation(RetryableOperation<X,Y> operation, String description, Object... args) throws X,Y {
		RetryableOperation<X,Y> operationInSession = () -> {
			int immediateRetriesLeft = 2;
			while (true) {
				try (var session = queryCollection.newSession()) {
					operation.run();
					session.commitTransactionIfAny();
				} catch (FailedMongoClientSessionException e) {
					setDisconnectedDriver(e);
					throw new DisconnectedException(e);
				} catch (MongoException e) {
					if (e.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR_LABEL)) {
						if (immediateRetriesLeft >= 1) {
							immediateRetriesLeft--;
							LOGGER.debug("Transient transaction error; retrying immediately", e);
							continue;
						} else {
							LOGGER.warn("Exhausted immediate retry attempts for transient transaction error", e);
							setDisconnectedDriver(e);
							throw new DisconnectedException(e);
						}
					} else {
						LOGGER.debug("MongoException is not recoverable; disconnecting", e);
						setDisconnectedDriver(e);
						throw new DisconnectedException(e);
					}
				}
				break;
			}
		};
		try (var _ = beginDriverOperation(description, args)) {
			try {
				operationInSession.run();
			} catch (DisconnectedException e) {
				LOGGER.debug("Driver is disconnected; will wait and retry operation ({})", e.getMessage());
				waitAndRetry(operationInSession, description, args);
			} catch (Exception e) {
				LOGGER.debug("Unexpected exception; will disconnect and retry operation", e);
				setDisconnectedDriver(e);
				waitAndRetry(operationInSession, description, args);
			} finally {
				LOGGER.debug("Finished operation " + description, args);
			}
		}
	}

	private <X extends Exception, Y extends Exception> void waitAndRetry(RetryableOperation<X, Y> operation, String description, Object... args) throws X, Y {
		testHooks.prePublicationWaitAction().run();
		formatDriverLock.lock();
		try {
			// There's a race here: the formatDriver could have recovered after we tried to use it
			// but before we acquired the lock. Let's double-check now to avoid an unnecessary wait
			// for an event that may already have happened.
			//
			// Note that this presupposes that formatDriver is always a DisconnectedDriver
			// at the time we tried to use it. This is not always true, so this can sometimes
			// skip the wait in cases where the wait would have prevented a user-visible
			// error. We should probably find a more meticulous approach here.

			if (formatDriver instanceof DisconnectedDriver<R>) {
				LOGGER.debug("Waiting for new FormatDriver for {} ms", reinitializationTimeout);
				boolean success = formatDriverChanged.await(reinitializationTimeout, MILLISECONDS);
				if (!success) {
					LOGGER.warn("Timed out waiting for MongoDB to recover; will retry anyway, but the operation may fail");
				}
			} else {
				LOGGER.debug("FormatDriver is already {}; no need to wait", formatDriver.getClass().getSimpleName());
			}
		} catch (InterruptedException e) {
			// In a library, it's hard to know what a user expects when interrupting a thread.
			// Usually they'd like to stop the current operation, but since most BoskDriver methods
			// don't throw InterruptedException, we have no clear way to report this to the application.
			//
			// We're going to assume the user would be satisfied if, instead of aborting,
			// the operation succeeded or failed immediately, and so we'll respond to the interruption
			// by retrying immediately. On failure, the exception thrown to the application
			// will be the natural one we would have thrown anyway; on success, the effect will be
			// as though it had succeeded on the first attempt and the retry hadn't been necessary.
			//
			// This is not ideal if the operation we're retrying is time-consuming,
			// because the interruption won't have the desired effect of stopping the operation,
			// and two interruptions would be required; but this seems like a decent compromise
			// until we devise something better. Hopefully this kind of behaviour is not too astonishing
			// to a user of a library that contains automatic retry logic.
			LOGGER.debug("Interrupted while waiting to retry; proceeding");
		} finally {
			formatDriverLock.unlock();
		}
		LOGGER.debug("Retrying " + description + " w/" + this.formatDriver.getClass().getSimpleName(), args);
		operation.run();
	}

	/**
	 * Sets {@link #formatDriver} but does not signal threads waiting on {@link #formatDriverChanged},
	 * because there's no point waking them to retry with {@link DisconnectedDriver}
	 * (which is bound to fail); they might as well keep waiting and hope for another
	 * better driver to arrive instead.
	 */
	void setDisconnectedDriver(Throwable reason) {
		LOGGER.debug("setDisconnectedDriver({}) (previously {})", reason.getClass().getSimpleName(), formatDriver.getClass().getSimpleName());
		FormatDriver<R> oldDriver;
		try {
			formatDriverLock.lock();
			oldDriver = formatDriver;
			oldDriver.close();
			formatDriver = new DisconnectedDriver<>(reason);
		} finally {
			formatDriverLock.unlock();
		}

		if (!(oldDriver instanceof DisconnectedDriver<?>)) {
			// The receiver is what reconnects us. Poke it to make sure it knows things
			// have gone south, and we need to try to reconnect.
			receiver.interrupt();
		}
	}

	/**
	 * Sets {@link #formatDriver} and also signals any threads waiting on {@link #formatDriverChanged}
	 * to retry their operation.
	 */
	void publishFormatDriver(FormatDriver<R> newFormatDriver) {
		LOGGER.debug("publishFormatDriver({}) (was {})", newFormatDriver.getClass().getSimpleName(), formatDriver.getClass().getSimpleName());
		try {
			formatDriverLock.lock();
			formatDriver.close();
			formatDriver = newFormatDriver;
			LOGGER.debug("Signaling");
			formatDriverChanged.signalAll();
		} finally {
			formatDriverLock.unlock();
		}
	}

	private interface RetryableOperation<X extends Exception, Y extends Exception> {
		void run() throws X,Y;
	}

	public static final String COLLECTION_NAME = "boskCollection";
	public static final BsonString MANIFEST_ID = new BsonString("!Manifest");
	private static final Exception FAILURE_TO_COMPUTE_INITIAL_STATE = new IllegalStateException("Failure to compute initial state");
	private static final Logger LOGGER = LoggerFactory.getLogger(MainDriver.class);
	private static final Logger UNINITIALIZED_COLLECTION_LOGGER = LoggerFactory.getLogger(UNINITIALIZED_COLLECTION_LOGGER_NAME);
}
