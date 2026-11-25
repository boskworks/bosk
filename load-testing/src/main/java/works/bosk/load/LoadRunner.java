package works.bosk.load;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.Histogram;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Reference;
import works.bosk.annotations.ReferencePath;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.Objects.requireNonNull;
import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.FAIL_FAST;

/**
 * Runs one {@link LoadConfig} and returns the measurements.
 *
 * <p>The run has two phases. During warmup the workers submit updates but their latencies are not
 * recorded. Then the measurement phase begins, the metrics sampler starts, and the workers record
 * submit and flush latency until the configured duration elapses. After the workers stop, the
 * harness flushes the driver and verifies the final state against the workload's expectation.
 */
public final class LoadRunner {
	public interface Refs {
		@ReferencePath("/catalog/-entity-/string")
		Reference<String> anyEntityString();
	}

	public LoadResult run(LoadConfig config) throws InvalidTypeException, IOException, InterruptedException {
		if (config.driver().isMongo()) {
			try (MongoEnvironment mongo = MongoEnvironment.start()) {
				mongo.dropCollection(MONGO_DATABASE, MONGO_COLLECTION);
				AtomicReference<MongoDriver> mongoDriver = new AtomicReference<>();
				try {
					return run(config, mongo, mongoDriver);
				} finally {
					MongoDriver driver = mongoDriver.get();
					if (driver != null) {
						driver.close();
					}
				}
			}
		} else {
			return run(config, null, new AtomicReference<>());
		}
	}

	private LoadResult run(LoadConfig config, MongoEnvironment mongo, AtomicReference<MongoDriver> mongoDriver) throws InvalidTypeException, IOException, InterruptedException {
		Instant startedAt = Instant.now();
		String runId = RUN_ID_FORMAT.format(startedAt.atOffset(ZoneOffset.UTC))
			+ (config.label().isEmpty() ? "" : "-" + config.label());
		EnvironmentInfo environment = EnvironmentInfo.capture();

		DisjointFieldUpdates workload = new DisjointFieldUpdates(config.entityCount(), config.threads(), config.valueBytes());
		Bosk<TestEntity> bosk = new Bosk<>(
			"load-" + UUID.randomUUID(),
			TestEntity.class,
			workload::initialRoot,
			BoskConfig.<TestEntity>builder()
				.driverFactory(driverFactory(config, mongo, mongoDriver))
				.hookOverloadThreshold(config.hookQueueMaxSize())
				.build());
		workload.initialize(bosk);
		AtomicBoolean hookActive = new AtomicBoolean(false);
		registerHooks(bosk, config, hookActive);
		hookActive.set(true);
		BoskDriver driver = bosk.driver();

		CountDownLatch start = new CountDownLatch(1);
		AtomicBoolean measuring = new AtomicBoolean(false);
		AtomicBoolean stop = new AtomicBoolean(false);
		LongAdder opsCounter = new LongAdder();
		LongAdder errors = new LongAdder();
		AtomicReference<Throwable> firstError = new AtomicReference<>();
		LatencyRecorder[] submitLatencies = new LatencyRecorder[config.threads()];
		LatencyRecorder[] flushLatencies = new LatencyRecorder[config.threads()];
		long[] opCounts = new long[config.threads()];
		Thread[] workers = new Thread[config.threads()];
		for (int i = 0; i < workers.length; i++) {
			submitLatencies[i] = new LatencyRecorder();
			flushLatencies[i] = new LatencyRecorder();
			int threadIndex = i;
			workers[i] = new Thread(
				() -> runWorker(threadIndex, workload, driver, config, start, measuring, stop, opsCounter, errors, firstError, submitLatencies[threadIndex], flushLatencies[threadIndex], opCounts),
				"load-worker-" + i);
			workers[i].setUncaughtExceptionHandler((_, error) -> {
				recordError(errors, firstError, error);
				stop.set(true);
			});
		}

		LatencyRecorder[] readLatencies = new LatencyRecorder[config.readers()];
		long[] readCounts = new long[config.readers()];
		Thread[] readers = new Thread[config.readers()];
		for (int i = 0; i < readers.length; i++) {
			readLatencies[i] = new LatencyRecorder();
			int threadIndex = i;
			readers[i] = new Thread(
				() -> runReader(threadIndex, workload, bosk, config, start, measuring, stop, readLatencies[threadIndex], readCounts, errors, firstError),
				"load-reader-" + i);
			readers[i].setUncaughtExceptionHandler((_, error) -> {
				recordError(errors, firstError, error);
				stop.set(true);
			});
		}
		Thread[] allThreads = concat(workers, readers);

		long heapUsedBefore = JvmMetrics.heapUsed();
		long gcCountBefore = JvmMetrics.gcCount();
		long gcTimeBefore = JvmMetrics.gcTimeMillis();

		MetricsSampler sampler = new MetricsSampler(opsCounter);
		boolean stalled = false;
		long measureStart = 0;
		long measureEnd = 0;
		try {
			for (Thread worker : allThreads) {
				worker.start();
			}
			start.countDown();
			Thread.sleep(config.warmup());
			sampler.start();
			measuring.set(true);
			measureStart = System.nanoTime();
			Thread.sleep(config.duration());
			measureEnd = System.nanoTime();
			measuring.set(false);
			stop.set(true);
			sampler.stop();
			stalled = joinWorkers(allThreads, config.stallTimeout());
		} catch (InterruptedException e) {
			stop.set(true);
			sampler.stop();
			for (Thread worker : allThreads) {
				worker.interrupt();
			}
			throw e;
		}

		long heapUsedAfter = JvmMetrics.heapUsed();
		long gcCountAfter = JvmMetrics.gcCount();
		long gcTimeAfter = JvmMetrics.gcTimeMillis();

		boolean verified = false;
		String verificationMessage = null;
		int verificationChecked = 0;
		int verificationFailed = 0;
		try {
			driver.flush();
			Verification verification = workload.verify(bosk);
			verificationChecked = verification.checked();
			verificationFailed = verification.failed();
			verified = verification.matched();
			verificationMessage = verification.message();
		} catch (IOException | InterruptedException | RuntimeException | AssertionError e) {
			verificationMessage = e.toString();
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		}

		long ops = 0;
		for (long opCount : opCounts) {
			ops += opCount;
		}
		long reads = 0;
		for (long readCount : readCounts) {
			reads += readCount;
		}
		long measuredNanos = Math.max(0, measureEnd - measureStart);

		return new LoadResult(
			runId,
			startedAt,
			config,
			environment,
			measuredNanos,
			ops,
			errors.sum(),
			reads,
			mergeLatencies(submitLatencies),
			mergeLatencies(flushLatencies),
			mergeLatencies(readLatencies),
			verified,
			verificationMessage,
			verificationChecked,
			verificationFailed,
			stalled,
			firstError.get(),
			heapUsedBefore,
			heapUsedAfter,
			gcCountBefore,
			gcCountAfter,
			gcTimeBefore,
			gcTimeAfter,
			sampler.samples()
		);
	}

	private static void runWorker(
		int threadIndex,
		Workload workload,
		BoskDriver driver,
		LoadConfig config,
		CountDownLatch start,
		AtomicBoolean measuring,
		AtomicBoolean stop,
		LongAdder opsCounter,
		LongAdder errors,
		AtomicReference<Throwable> firstError,
		LatencyRecorder submitLatency,
		LatencyRecorder flushLatency,
		long[] opCounts
	) {
		try {
			start.await();
			long iteration = 0;
			while (!stop.get()) {
				boolean record = measuring.get();
				long operationStart = System.nanoTime();
				try {
					workload.submit(driver, threadIndex, iteration++);
				} catch (RuntimeException e) {
					recordError(errors, firstError, e);
					continue;
				}
				long operationEnd = System.nanoTime();
				if (record) {
					submitLatency.record(operationEnd - operationStart);
				}
				if (shouldFlush(config, iteration)) {
					long flushStart = System.nanoTime();
					try {
						driver.flush();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						recordError(errors, firstError, e);
						return;
					} catch (IOException e) {
						recordError(errors, firstError, e);
						continue;
					}
					long flushEnd = System.nanoTime();
					if (record) {
						flushLatency.record(flushEnd - flushStart);
					}
				}
				if (record) {
					opCounts[threadIndex]++;
					opsCounter.increment();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static boolean shouldFlush(LoadConfig config, long iteration) {
		return switch (config.flushMode()) {
			case EVERY_OP -> true;
			case EVERY_N -> iteration % config.flushEvery() == 0;
			case END_ONLY -> false;
		};
	}

	private static void runReader(
		int readerIndex,
		Workload workload,
		Bosk<TestEntity> bosk,
		LoadConfig config,
		CountDownLatch start,
		AtomicBoolean measuring,
		AtomicBoolean stop,
		LatencyRecorder readLatency,
		long[] readCounts,
		LongAdder errors,
		AtomicReference<Throwable> firstError
	) {
		try {
			start.await();
			if (config.readers() == 0) {
				return;
			}
			int index = (int) ((long) config.entityCount() * readerIndex / config.readers());
			Reference<TestEntity> entity = workload.entity(bosk, index);
			Reference<?> target = switch (config.readShape()) {
				case LEAF -> entity.then(String.class, TestEntity.Fields.string);
				case ENTITY -> entity;
				case CATALOG -> bosk.rootReference().thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
			};
			if (config.sessionLifetime() == SessionLifetime.PER_THREAD) {
				try (var _ = bosk.readSession()) {
					readLoop(target, readerIndex, measuring, stop, readLatency, readCounts);
				}
			} else {
				while (!stop.get()) {
					boolean record = measuring.get();
					long readStart = System.nanoTime();
					try (var _ = bosk.readSession()) {
						target.value();
					} catch (RuntimeException e) {
						recordError(errors, firstError, e);
						continue;
					}
					long readEnd = System.nanoTime();
					if (record) {
						readLatency.record(readEnd - readStart);
						readCounts[readerIndex]++;
					}
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (InvalidTypeException e) {
			recordError(errors, firstError, e);
		} catch (RuntimeException e) {
			recordError(errors, firstError, e);
		}
	}

	private static void readLoop(
		Reference<?> target,
		int readerIndex,
		AtomicBoolean measuring,
		AtomicBoolean stop,
		LatencyRecorder readLatency,
		long[] readCounts
	) {
		while (!stop.get()) {
			boolean record = measuring.get();
			long readStart = System.nanoTime();
			target.value();
			long readEnd = System.nanoTime();
			if (record) {
				readLatency.record(readEnd - readStart);
				readCounts[readerIndex]++;
			}
		}
	}

	private static Thread[] concat(Thread[] a, Thread[] b) {
		Thread[] result = new Thread[a.length + b.length];
		System.arraycopy(a, 0, result, 0, a.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}

	private static boolean joinWorkers(Thread[] workers, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		boolean stalled = false;
		for (Thread worker : workers) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				stalled = true;
				break;
			}
			worker.join(Duration.ofNanos(remaining));
			if (worker.isAlive()) {
				stalled = true;
				break;
			}
		}
		if (stalled) {
			for (Thread worker : workers) {
				worker.interrupt();
			}
			dumpThreads();
		}
		return stalled;
	}

	private static void dumpThreads() {
		System.err.println("Load test stalled; dumping threads");
		Thread.getAllStackTraces().forEach((thread, stack) -> {
			System.err.println(thread);
			for (StackTraceElement element : stack) {
				System.err.println("\tat " + element);
			}
		});
	}

	private static void recordError(LongAdder errors, AtomicReference<Throwable> firstError, Throwable error) {
		errors.increment();
		firstError.compareAndSet(null, error);
	}

	private static LatencySummary mergeLatencies(LatencyRecorder[] recorders) {
		Histogram merged = LatencyRecorder.newHistogram();
		for (LatencyRecorder recorder : recorders) {
			recorder.addTo(merged);
		}
		return LatencySummary.of(merged);
	}

	private static void registerHooks(Bosk<TestEntity> bosk, LoadConfig config, AtomicBoolean hookActive) throws InvalidTypeException {
		if (config.hookCount() == 0) {
			return;
		}
		if (config.hookScope() == HookScope.ROOT) {
			for (int i = 0; i < config.hookCount(); i++) {
				bosk.hookRegistrar().registerHook("load-hook-" + i, bosk.rootReference(),
					_ -> sleepIfActive(hookActive, config));
			}
		} else {
			Reference<String> scope = bosk.buildReferences(Refs.class).anyEntityString();
			for (int i = 0; i < config.hookCount(); i++) {
				bosk.hookRegistrar().registerHook("load-hook-" + i, scope,
					_ -> sleepIfActive(hookActive, config));
			}
		}
	}

	private static void sleepIfActive(AtomicBoolean hookActive, LoadConfig config) throws InterruptedException {
		if (hookActive.get() && !config.hookLatency().isZero()) {
			Thread.sleep(config.hookLatency());
		}
	}

	private static DriverFactory<TestEntity> driverFactory(LoadConfig config, MongoEnvironment mongo, AtomicReference<MongoDriver> mongoDriver) {
		return switch (config.driver()) {
			case SIMPLE -> BoskConfig.simpleDriver();
			case MONGO_SEQUOIA, MONGO_PANDO, MONGO_PANDO_GRAFTED -> {
				MongoEnvironment environment = requireNonNull(mongo, "mongo environment");
				MongoDriverSettings settings = MongoDriverSettings.builder()
					.database(MONGO_DATABASE)
					.collection(MONGO_COLLECTION)
					.preferredDatabaseFormat(format(config.driver()))
					.initialDatabaseUnavailableMode(FAIL_FAST)
					.build();
				yield (boskInfo, downstream) -> {
					MongoDriver driver = MongoDriver.<TestEntity>factory(environment.clientSettings(), settings, new BsonSerializer())
						.build(boskInfo, downstream);
					mongoDriver.set(driver);
					return driver;
				};
			}
		};
	}

	private static MongoDriverSettings.DatabaseFormat format(DriverKind driver) {
		return switch (driver) {
			case MONGO_SEQUOIA -> SEQUOIA;
			case MONGO_PANDO -> PandoFormat.oneBigDocument();
			case MONGO_PANDO_GRAFTED -> PandoFormat.withGraftPoints("/catalog", "/sideTable");
			case SIMPLE -> throw new IllegalArgumentException("The simple driver has no Mongo format");
		};
	}

	private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final String MONGO_DATABASE = "bosk-load";
	private static final String MONGO_COLLECTION = "state";
}
