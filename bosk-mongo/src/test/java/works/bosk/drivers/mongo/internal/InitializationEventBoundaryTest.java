package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.slf4j.event.Level.WARN;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests that a driver that initializes a collection ignores change events that
 * happened before the initialization.
 * <p>
 * The change stream cursor opens before the state is loaded, so it can deliver
 * events from before the collection was initialized. Those events describe a
 * state that the newly initialized driver has already replaced.
 */
@ReplayLogsOnFailure
@InjectFields
public class InitializationEventBoundaryTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(PandoFormat.withGraftPoints("/catalog", "/sideTable")),
			Stream.of(TestParameters.EventTiming.NORMAL)
		);
	}

	@Test
	void eventsBeforeInitialization_areIgnored() throws InvalidTypeException, IOException, InterruptedException {
		// This test deliberately empties the collection under a driver's feet.
		suppressLogging(WARN, MainDriver.class, ChangeReceiver.class);

		Bosk<TestEntity> initializer = newBosk();
		initializer.driver().flush();

		ErrorRecordingChangeListener.ErrorRecorder recorder = new ErrorRecordingChangeListener.ErrorRecorder();
		CountDownLatch cursorOpened = new CountDownLatch(1);
		CountDownLatch resume = new CountDownLatch(1);
		AtomicReference<Bosk<TestEntity>> readerRef = new AtomicReference<>();
		AtomicReference<Throwable> readerFailure = new AtomicReference<>();
		Thread readerThread = new Thread(() -> {
			MainDriver.setProbes(TestProbes.noop()
				.withListenerFactory(downstream -> new BlockingListener(recorder, downstream, cursorOpened, resume)));
			try {
				readerRef.set(newBosk());
			} catch (Throwable t) {
				readerFailure.set(t);
			} finally {
				MainDriver.resetProbes();
			}
		}, "reader-construction");
		readerThread.start();

		assertTrue(cursorOpened.await(30, SECONDS), "Reader's change stream cursor must open");
		// Empty the collection while the reader's cursor is open but before it has
		// loaded the state. The state documents go first and the manifest last,
		// which is what makes the reader treat the collection as uninitialized.
		MongoCollection<BsonDocument> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
		collection.deleteMany(new BsonDocument("_id", new BsonDocument("$ne", MANIFEST_ID)));
		collection.deleteOne(new BsonDocument("_id", MANIFEST_ID));
		resume.countDown();

		readerThread.join(30_000);
		assertFalse(readerThread.isAlive(), "Reader construction must finish");
		assertNull(readerFailure.get(), "Reader construction failed: " + readerFailure.get());

		// A third bosk generates an update that is ordered after the reader's
		// initialization, so waiting for the reader to apply it also waits for the
		// reader to process the older events.
		Bosk<TestEntity> reader = readerRef.get();
		Bosk<TestEntity> updater = newBosk();
		Refs updaterRefs = updater.buildReferences(Refs.class);
		updater.driver().flush();
		updater.driver().submitReplacement(
			updaterRefs.catalog().then(entity123).then(String.class, "string"), "after");
		updater.driver().flush();
		reader.driver().flush();

		assertEquals(List.of(), recorder.disconnections,
			"The reader must not disconnect because of events from before its initialization");
		assertEquals(0, recorder.failureCount, "Unexpected connection failures: " + recorder);
	}

	private Bosk<TestEntity> newBosk() throws InvalidTypeException {
		return new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
	}

	/**
	 * Blocks the reader inside {@link ChangeListener#onConnectionSucceeded()},
	 * after its change stream cursor is open but before it loads the state.
	 */
	private static class BlockingListener extends ErrorRecordingChangeListener {
		private final CountDownLatch cursorOpened;
		private final CountDownLatch resume;

		BlockingListener(ErrorRecorder errorRecorder, ChangeListener downstream, CountDownLatch cursorOpened, CountDownLatch resume) {
			super(errorRecorder, downstream);
			this.cursorOpened = cursorOpened;
			this.resume = resume;
		}

		@Override
		public void onConnectionSucceeded() throws UnrecognizedFormatException, InterruptedException, IOException, InitialStateException, TimeoutException, FailedMongoClientSessionException, InvalidCollectionContentsException {
			cursorOpened.countDown();
			if (!resume.await(30, SECONDS)) {
				throw new TimeoutException("Test did not allow initialization to proceed");
			}
			super.onConnectionSucceeded();
		}
	}
}
