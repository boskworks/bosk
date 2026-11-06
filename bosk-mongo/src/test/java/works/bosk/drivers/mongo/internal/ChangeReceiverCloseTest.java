package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import works.bosk.Identifier;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests that closing a {@link ChangeReceiver} doesn't interrupt a connection
 * setup that's in progress.
 * <p>
 * Interrupting a database command partway through leaves the MongoDB client
 * library unable to abort the transaction that command belongs to, and so the
 * server holds the transaction's locks until it reaps the transaction, up to a
 * minute later. That's slow enough to strand other operations that need those
 * locks, so {@link ChangeReceiver#close()} waits for the setup instead.
 */
@InjectFields
@ReplayLogsOnFailure
public class ChangeReceiverCloseTest extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(MongoDriverSettings.DatabaseFormat.SEQUOIA),
			Stream.of(TestParameters.EventTiming.NORMAL)
		).map(b -> b.applyDriverSettings(s -> s
			// Long enough that the "still waiting" check below can't race the
			// timeout, but the test still finishes as soon as the setup is released.
			.timescaleMS(2000)
		));
	}

	@Test
	void close_waitsForConnectionSetupToFinish() throws InterruptedException {
		CountDownLatch setupStarted = new CountDownLatch(1);
		CountDownLatch setupMayFinish = new CountDownLatch(1);
		AtomicBoolean setupWasInterrupted = new AtomicBoolean(false);
		ChangeListener listener = new ChangeListener() {
			@Override
			public void onConnectionSucceeded() {
				setupStarted.countDown();
				try {
					setupMayFinish.await();
				} catch (InterruptedException e) {
					setupWasInterrupted.set(true);
				}
			}

			@Override
			public void onEvent(ChangeStreamDocument<BsonDocument> event) {
			}

			@Override
			public void onConnectionFailed(Exception cause) {
			}

			@Override
			public void onDisconnect(Throwable e) {
			}
		};
		ChangeReceiver receiver = new ChangeReceiver(
			boskName(),
			Identifier.from("receiver-close-test"),
			listener,
			driverSettings,
			100,
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(driverSettings.collection(), BsonDocument.class));
		try {
			assertTrue(setupStarted.await(10, SECONDS), "Connection setup never started");

			Thread closer = new Thread(receiver::close, "closer");
			closer.start();
			closer.join(MILLISECONDS.toMillis(500));
			assertTrue(closer.isAlive(),
				"close() must wait for the connection setup rather than interrupting it");

			setupMayFinish.countDown();
			closer.join(5_000);
			assertFalse(closer.isAlive(), "close() should return once the setup finishes");
			assertFalse(setupWasInterrupted.get(), "close() must not interrupt the connection setup");
		} finally {
			setupMayFinish.countDown();
			receiver.close();
		}
	}
}
