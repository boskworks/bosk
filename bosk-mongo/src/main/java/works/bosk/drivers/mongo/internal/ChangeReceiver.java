package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.Closeable;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.BsonDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import works.bosk.Identifier;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.drivers.mongo.exceptions.InitialCursorCommandException;
import works.bosk.drivers.mongo.exceptions.InitialCursorTimeoutException;
import works.bosk.drivers.mongo.exceptions.InitialRootFailureException;
import works.bosk.logging.MdcKeys;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.FAIL_FAST;
import static works.bosk.logging.MappedDiagnosticContext.MDCScope;
import static works.bosk.logging.MappedDiagnosticContext.setupMDC;

/**
 * Houses a background thread that repeatedly initializes, processes, and closes a change stream cursor.
 * Ideally, the opening and closing happen just once, but they're done in a loop for fault tolerance,
 * so that the driver can reinitialize if certain unusual conditions arise.
 *
 * <p>
 * We're maintaining an in-memory replica of database state, and so
 * the loading of database state and the subsequent handling of events are inherently coupled.
 * To coordinate this, we call back into {@link ChangeListener#onConnectionSucceeded()} to indicate the
 * appropriate time to load the initial state before any event processing begins.
 * The code ends up looking a bit complicated, with {@link MainDriver} creating a {@link ChangeReceiver}
 * that calls back into {@link MainDriver} via {@link ChangeListener};
 * but it eliminates all race conditions
 * (which dramatically simplifies the reasoning about parallelism and corner cases)
 * because the state loading and event processing happen on the same thread.
 */
class ChangeReceiver implements Closeable {
	private final String boskName;
	private final Identifier boskID;
	private final ChangeListener listener;
	private final MongoDriverSettings settings;
	private final MongoCollection<BsonDocument> collection;
	private final ScheduledExecutorService ex = Executors.newScheduledThreadPool(1);
	private final Exception creationPoint;
	private volatile boolean isClosed = false;

	ChangeReceiver(String boskName, Identifier boskID, ChangeListener listener, MongoDriverSettings settings, MongoCollection<BsonDocument> collection) {
		this.boskName = boskName;
		this.boskID = boskID;
		this.listener = listener;
		this.settings = settings;
		this.collection = collection;
		this.creationPoint = new Exception("Additional context: ChangeReceiver creation stack trace:");
		if (settings.initialDatabaseUnavailableMode() == FAIL_FAST) {
			// User requested fail-fast behaviour; try to open the cursor right away
			// to ensure the database is set up for change streams.
			probeChangeStreamCursor();
		}
		ex.scheduleWithFixedDelay(
			this::connectionLoop,
			0,
			settings.timescaleMS(),
			MILLISECONDS
		);
	}

	private void probeChangeStreamCursor() {
		try (var _ = openCursor()) {
			LOGGER.debug("Successfully opened MongoDB cursor");
		} catch (MongoTimeoutException e) {
			throw new InitialCursorTimeoutException("Timed out attempting to open MongoDB cursor; check database connectivity", e);
		} catch (MongoCommandException e) {
			throw new InitialCursorCommandException("Failed to open change stream cursor; ensure MongoDB server configured as a replica set", e);
		} catch (MongoException e) {
			throw new InitialRootFailureException("Unexpected failure opening MongoDB cursor", e);
		}
	}

	@Override
	public void close() {
		isClosed = true;
		ex.shutdownNow();
		// Note: don't awaitTermination. It seems like a good idea, but it makes the tests crawl, and it doesn't matter for correctness
	}

	/**
	 * This method has a loop to do immediate reconnections and skip the
	 * {@link MongoDriverSettings#timescaleMS()} delay,
	 * but besides that, exiting this method has the same effect as continuing
	 * around the loop.
	 */
	private void connectionLoop() {
		String oldThreadName = currentThread().getName();
		currentThread().setName(getClass().getSimpleName() + " [" + boskName + "]");
		try (MDCScope _ = setupMDC(boskName, boskID)) {
			LOGGER.debug("Starting connectionLoop task");
			try {
				while (!isClosed) {
					// Design notes:
					//
					// For the following try-catch clause, a `continue` causes us to attempt an immediate reconnection,
					// while a `return` causes us to wait for the "recovery polling" interval to elapse first.
					// When in doubt, `return` is a bit safer because it's unlikely to cause a spin-loop of rapid reconnections.
					//
					// Log `warn` and `error` levels are likely to be logged by applications in production,
					// and so they will be visible to whatever team is operating the application that uses Bosk.
					// They should be written with a reader in mind who is not a Bosk expert, perhaps not even
					// knowing what bosk is, and should contain enough information to guide their troubleshooting efforts.
					//
					// Logs at `info` levels can target knowledgeable Bosk users, and should aim to explain what
					// the library is doing in a way that helps them learn to use it more effectively.
					//
					// Logs at the `debug` level target Bosk developers. They can use some Bosk jargon, though
					// they should also be helping new Bosk developers climb the learning curve. They should
					// allow developers to tell what code paths executed.
					//
					// Logs at the `trace` level target expert Bosk developers troubleshooting very tricky bugs,
					// and can include information that would be too voluminous to emit under most circumstances.
					// Examples include stack traces for routine situations, or dumps of entire data structures,
					// neither of which should be done at the `debug` level. It can also include high-frequency messages
					// emitted many times for a single user action (again, not recommended at the `debug` level),
					// though this must be done cautiously, since even disabled log statements still have nonzero overhead.
					//
					LOGGER.debug("Opening cursor");
					try (var cursor = openCursor()) {
						try {
							try {
								listener.onConnectionSucceeded();

								// Note that eventLoop does not throw RuntimeException; therefore,
								// any RuntimeException must have occurred before this point.
								// TODO: Two try blocks?
								eventLoop(cursor);
							} finally {
								if (isClosed) {
									LOGGER.debug("Cursor is already closed; skipping usual error handling");
									return;
								}
							}
						} catch (UnprocessableEventException | UnexpectedEventProcessingException e) {
							disconnect("Unable to process MongoDB change event", REMEDY_CONTINUE, e);
							// Reconnection will skip this event, so it's safe to try it right away
							continue;
						} catch (InterruptedException e) {
							disconnect("Interrupted while processing MongoDB change events", REMEDY_CONTINUE, e);
							continue;
						} catch (IOException | FailedSessionException e) {
							disconnect("Unexpected exception while processing MongoDB change events", REMEDY_RETURN, e);
							return;
						} catch (UnrecognizedFormatException e) {
							disconnect("Unrecognized MongoDB database content format", REMEDY_RETURN, e);
							return;
						} catch (UninitializedCollectionException e) {
							disconnect("MongoDB collection is not initialized", REMEDY_RETURN, e);
							return;
						} catch (InitialRootActionException e) {
							disconnect("Unable to initialize bosk state", REMEDY_RETURN, e);
							return;
						} catch (TimeoutException e) {
							disconnect("Timed out waiting for bosk state to initialize", REMEDY_RETURN, e);
							return;
						} catch (DisconnectedException e) {
							addContextToException(e);
							LOGGER.warn("Driver is disconnected; will wait and retry", e);
							return;
						} catch (RuntimeException | Error e) {
							disconnect("Unexpected exception after connecting to MongoDB", REMEDY_RETURN, e);
							return;
						}
					} catch (RuntimeException e) {
						addContextToException(e);
						LOGGER.warn("Unable to connect to MongoDB database; will wait and retry", e);
						try {
							listener.onConnectionFailed();
						} catch (InterruptedException | TimeoutException e2) {
							addContextToException(e);
							LOGGER.error("Error while running MongoDB connection failure handler; will wait and reconnect", e2);
						}
						return;
					}
					LOGGER.trace("Change event processing returned normally");
				}
			} finally {
				LOGGER.debug("Ending connectionLoop task; isClosed={}", isClosed);
				currentThread().setName(oldThreadName);
			}
		} catch (RuntimeException e) {
			addContextToException(e);
			LOGGER.warn("connectionLoop task ended with unexpected {}; discarding", e.getClass().getSimpleName(), e);
		}
	}

	private void disconnect(String description, String remedy, Throwable e) {
		addContextToException(e);
		if (isClosed) {
			LOGGER.debug("(Driver is already closed) {}", description, e);
		} else {
			LOGGER.warn("{}; {}", description, remedy, e);
		}
		listener.onDisconnect(e);
	}

	private void addContextToException(Throwable x) {
		x.addSuppressed(creationPoint);
	}

	private MongoChangeStreamCursor<ChangeStreamDocument<BsonDocument>> openCursor() {
		MongoChangeStreamCursor<ChangeStreamDocument<BsonDocument>> result = collection
			.watch()
			.maxAwaitTime(settings.timescaleMS(), MILLISECONDS)
			.cursor();
		LOGGER.debug("Cursor is open");
		return result;
	}

	/**
	 * Should not throw RuntimeException, or else {@link #connectionLoop()} is likely to overreact.
	 */
	private void eventLoop(MongoChangeStreamCursor<ChangeStreamDocument<BsonDocument>> cursor) throws UnprocessableEventException, UnexpectedEventProcessingException {
		if (isClosed) {
			LOGGER.debug("Receiver is closed");
			return;
		}
		try {
			LOGGER.debug("Starting event loop");
			while (!isClosed) {
				ChangeStreamDocument<BsonDocument> event;
				try {
					event = cursor.next();
				} catch (NoSuchElementException e) {
					LOGGER.debug("Cursor is finished");
					break;
				} catch (MongoInterruptedException e) {
					LOGGER.debug("Interrupted while waiting for change event: {}", e.toString());
					break;
				}
				if (!isClosed) {
					processEvent(event);
				}
			}
		} catch (DisconnectedException e) {
			LOGGER.trace("connectionLoop can handle this exception; rethrow it", e);
			throw e;
		} catch (RuntimeException e) {
			addContextToException(e);
			LOGGER.debug("Unexpected {} while processing events", e.getClass().getSimpleName(), e);
			throw new UnexpectedEventProcessingException(e);
		} finally {
			LOGGER.debug("Exited event loop");
		}
	}

	private void processEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		if (settings.testing().eventDelayMS() > 0) {
			LOGGER.debug("| eventDelayMS {}ms ", settings.testing().eventDelayMS());
			try {
				Thread.sleep(settings.testing().eventDelayMS());
			} catch (InterruptedException e) {
				LOGGER.debug("| Interrupted");
			}
		}
		try {
			MDC.put(MdcKeys.EVENT, "e" + EVENT_COUNTER.incrementAndGet());
			switch (event.getOperationType()) {
				case INSERT:
				case UPDATE:
				case REPLACE:
				case DELETE:
					listener.onEvent(event);
					break;
				case RENAME:
				case DROP:
				case DROP_DATABASE:
				case INVALIDATE:
				case OTHER:
					throw new UnprocessableEventException("Disruptive event received", event.getOperationType());
			}
		} finally {
			MDC.remove(MdcKeys.EVENT);
		}
	}

	private static final AtomicLong EVENT_COUNTER = new AtomicLong(0);
	private static final Logger LOGGER = LoggerFactory.getLogger(ChangeReceiver.class);

	/**
	 * Suitable for {@code continue} in {@link #connectionLoop()}
	 */
	private static final String REMEDY_CONTINUE = "reconnecting";

	/**
	 * Suitable for {@code return} in {@link #connectionLoop()}
	 */
	private static final String REMEDY_RETURN = "will wait and retry";
}
