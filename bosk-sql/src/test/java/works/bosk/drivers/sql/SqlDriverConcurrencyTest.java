package works.bosk.drivers.sql;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.annotations.ReferencePath;
import works.bosk.drivers.sql.SqlTestService.Database;
import works.bosk.drivers.sql.schema.Schema;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestEntity.Fields;
import works.bosk.testing.drivers.state.TestValues;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.drivers.sql.SqlTestService.sqlDriverFactory;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests that concurrent {@code submit*} operations on two {@link SqlDriver}s
 * sharing one database don't lose updates. The driver implements each submit as
 * a read-modify-write of the {@code BOSK.STATE} row, so without row locking two
 * concurrent submissions can each read the same state and the later write
 * silently discards the earlier one.
 * <p>
 * The test coordinates the two submissions with the {@link TestProbes} probes so
 * that the interleaving is deterministic: the first submission reads the state
 * row and is then paused (holding the row lock, once the fix is in place)
 * before it writes; the second submission is started while the first is paused.
 * <p>
 * Without {@code SELECT ... FOR UPDATE}, the second submission's read completes
 * immediately, so it too is based on the initial state and one of the two
 * updates is lost. With the lock, the second submission's read blocks until the
 * first commits, so it reads the first's update and applies its own on top.
 * The test waits a bounded time for the second read to complete, because the
 * two cases cannot be told apart in any other way: waiting indefinitely for the
 * second read would deadlock once the fix makes that read block behind the
 * paused first submission.
 */
@Testcontainers
@InjectFields
@InjectFrom({DatabaseInjector.class})
class SqlDriverConcurrencyTest {
	@Injected Database database;

	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	private final AtomicInteger dbCounter = new AtomicInteger(0);
	private SqlDriverSettings settings;
	private HikariDataSource dataSource;

	@BeforeEach
	void setupDriverFactory() {
		settings = new SqlDriverSettings(50, 100);
		String databaseName = SqlDriverConcurrencyTest.class.getSimpleName()
			+ dbCounter.incrementAndGet();
		dataSource = database.dataSourceFor(databaseName);
	}

	@AfterEach
	void runTearDown() throws SQLException {
		SqlDriverImpl.TEST_PROBES.remove();
		tearDownActions.forEach(Runnable::run);
		try (
			var c = dataSource.getConnection()
		) {
			new Schema().dropTables(c);
		}
	}

	@Test
	void concurrentSubmissionsToDistinctNodes_mustNotLoseUpdates() throws InvalidTypeException, IOException {
		CountDownLatch firstReadDone = new CountDownLatch(1);
		CountDownLatch firstRelease = new CountDownLatch(1);
		CountDownLatch secondReadDone = new CountDownLatch(1);
		CountDownLatch secondRelease = new CountDownLatch(1);

		// The drivers are built when the Bosks are constructed, on this thread,
		// so each captures the hooks appropriate to it.
		SqlDriverImpl.TEST_PROBES.set(TestProbes.noop()
			.withAfterStateRead(() -> {
				firstReadDone.countDown();
				await(firstRelease, Duration.ofSeconds(30));
			}));
		Bosk<TestEntity> firstBosk = createBosk("first");
		SqlDriverImpl.TEST_PROBES.set(TestProbes.noop()
			.withAfterStateRead(() -> {
				secondReadDone.countDown();
				await(secondRelease, Duration.ofSeconds(30));
			}));
		Bosk<TestEntity> secondBosk = createBosk("second");
		SqlDriverImpl.TEST_PROBES.remove();

		Refs firstRefs = firstBosk.buildReferences(Refs.class);
		Refs secondRefs = secondBosk.buildReferences(Refs.class);

		// First submission: reads the state, then pauses before writing.
		Submission first = Submission.start(firstBosk, () -> firstBosk.driver().submitReplacement(firstRefs.string(), "first write"));
		await(firstReadDone, Duration.ofSeconds(30));

		// Second submission: started while the first is paused. Without row
		// locking its read completes immediately (seeing the same initial state);
		// with SELECT ... FOR UPDATE it blocks until the first submission commits.
		Submission second = Submission.start(secondBosk, () -> secondBosk.driver().submitReplacement(secondRefs.valuesString(), "second write"));
		boolean secondReadWhileFirstPaused = tryAwait(secondReadDone, Duration.ofSeconds(5));

		// Let the first submission write and commit.
		firstRelease.countDown();
		join(first.thread());

		// If the second submission's read was blocked, it can proceed now.
		if (!secondReadWhileFirstPaused) {
			await(secondReadDone, Duration.ofSeconds(30));
		}
		secondRelease.countDown();
		join(second.thread());

		assertNoError(first.error());
		assertNoError(second.error());

		// The in-memory state of firstBosk and secondBosk is updated by their
		// listeners asynchronously, so verify the database contents directly.
		Bosk<TestEntity> checkBosk = createBosk("check");
		Refs checkRefs = checkBosk.buildReferences(Refs.class);
		try (var _ = checkBosk.readSession()) {
			assertEquals("first write", checkRefs.string().value());
			assertEquals("second write", checkRefs.valuesString().value());
		}
	}

	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
		@ReferencePath("/values/string") Reference<String> valuesString();
	}

	private TestEntity initialState(Bosk<TestEntity> bosk) throws InvalidTypeException {
		return TestEntity.empty(Identifier.from("root"), bosk.rootReference().thenCatalog(TestEntity.class, Path.just(Fields.catalog)))
			.withValues(Optional.of(TestValues.blank()));
	}

	private Bosk<TestEntity> createBosk(String prefix) {
		return new Bosk<>(
			boskName(prefix),
			TestEntity.class,
			this::initialState,
			BoskConfig.<TestEntity>builder()
				.driverFactory(driverFactory())
				.build());
	}

	private DriverFactory<TestEntity> driverFactory() {
		return (boskInfo, downstream) -> {
			var driver = sqlDriverFactory(settings, dataSource).build(boskInfo, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};
	}

	private record Submission(Thread thread, AtomicReference<Throwable> error) {
		static Submission start(Bosk<TestEntity> bosk, Runnable submit) {
			AtomicReference<Throwable> error = new AtomicReference<>();
			Thread thread = new Thread(() -> {
				try {
					submit.run();
				} catch (Throwable e) {
					error.set(e);
				}
			}, "submitter for " + bosk.name());
			thread.start();
			return new Submission(thread, error);
		}
	}

	private static void await(CountDownLatch latch, Duration timeout) {
		try {
			if (!latch.await(timeout.toMillis(), MILLISECONDS)) {
				throw new AssertionError("Timed out waiting for coordination signal");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for coordination signal", e);
		}
	}

	private static boolean tryAwait(CountDownLatch latch, Duration timeout) {
		try {
			return latch.await(timeout.toMillis(), MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for coordination signal", e);
		}
	}

	private static void join(Thread thread) {
		try {
			thread.join(SECONDS.toMillis(60));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for " + thread.getName(), e);
		}
		if (thread.isAlive()) {
			throw new AssertionError(thread.getName() + " should have finished");
		}
	}

	private static void assertNoError(AtomicReference<Throwable> error) {
		if (error.get() != null) {
			throw new AssertionError("Submission failed", error.get());
		}
	}
}
