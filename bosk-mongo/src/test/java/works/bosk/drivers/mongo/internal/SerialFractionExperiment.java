package works.bosk.drivers.mongo.internal;

import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.drivers.ForwardingDriver;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.BoskLogFilter;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Finds the serial bottleneck behind the plateau in
 * {@link ParallelUpdateExperiment}.
 * <p>
 * The update threads only wait for their database transactions to commit; the
 * change-stream receiver applies events to the local replica asynchronously.
 * This experiment times the receiver's handling of each event and the
 * downstream apply it performs, so we can tell whether the plateau is the
 * receiver, the local apply, or the database itself.
 * <p>
 * It is skipped unless {@code EXPERIMENT_THREADS} is set. It reports, for each
 * measurement window:
 * <ul>
 * <li>{@code updatesPerSec}: what the update threads achieve</li>
 * <li>{@code eventsPerSec}: how fast the receiver drains change events</li>
 * <li>{@code eventBusy}: the fraction of wall time the receiver spends inside
 * {@link ChangeListener#onEvent}, which is at most 1 for one receiver thread</li>
 * <li>{@code nsPerEvent}: mean time inside {@code onEvent}</li>
 * <li>{@code nsPerDownstream}: mean time inside the downstream driver's update
 * methods, which is part of {@code onEvent}</li>
 * <li>{@code lag}: updates submitted minus events seen since the end of the
 * warm-up, which grows without bound if the receiver cannot keep up</li>
 * </ul>
 * Environment variables:
 * <ul>
 * <li>{@code EXPERIMENT_THREADS} (required): number of update threads</li>
 * <li>{@code EXPERIMENT_MODE}: {@code field} (default) or {@code replace}</li>
 * <li>{@code EXPERIMENT_CATALOG_SIZE}: number of catalog entries to create
 * (default: same as the thread count); larger than the thread count gives a
 * fixed, larger state tree</li>
 * <li>{@code EXPERIMENT_DOWNSTREAM}: {@code normal} (default) or
 * {@code discard}; the latter swallows downstream updates, so the local
 * state never changes</li>
 * <li>{@code EXPERIMENT_RECEIVER}: {@code normal} (default) or {@code drop};
 * the latter discards each event instead of handing it to the format driver,
 * measuring the raw event drain rate</li>
 * <li>{@code EXPERIMENT_RAW}: {@code true} runs plain Mongo updates instead
 * of the bosk driver, giving the database's own throughput ceiling for the
 * same field update</li>
 * <li>{@code EXPERIMENT_TXN}: with {@code EXPERIMENT_RAW}, {@code true}
 * (default) wraps each raw update in a transaction and {@code false} uses a
 * plain retryable write, showing what the transaction costs</li>
 * <li>{@code EXPERIMENT_TXN_WC}: with {@code EXPERIMENT_RAW}, {@code majority}
 * (default), {@code w1}, or {@code default}, selecting the write concern and
 * hence whether the write waits for the journal</li>
 * <li>{@code EXPERIMENT_WARMUP_MS}, {@code EXPERIMENT_MEASURE_MS},
 * {@code EXPERIMENT_REPS} as in {@link ParallelUpdateExperiment}</li>
 * <li>{@code EXPERIMENT_THREAD_DUMP}: {@code true} prints a thread dump after
 * the measurement windows</li>
 * </ul>
 */
@ReplayLogsOnFailure
@InjectFields
public class SerialFractionExperiment extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"SerialFractionExperiment",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog"))
				.timescaleMS(LONG_TIMESCALE)
				.database("SerialFractionExperiment")));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "EXPERIMENT_THREADS", matches = ".+")
	void experiment(TestInfo testInfo) throws Exception {
		experimentThreads = Integer.parseInt(System.getenv("EXPERIMENT_THREADS"));
		mode = System.getenv().getOrDefault("EXPERIMENT_MODE", "field");
		catalogSize = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_CATALOG_SIZE", Integer.toString(experimentThreads)));
		String downstreamMode = System.getenv().getOrDefault("EXPERIMENT_DOWNSTREAM", "normal");
		String receiverMode = System.getenv().getOrDefault("EXPERIMENT_RECEIVER", "normal");
		boolean raw = Boolean.parseBoolean(System.getenv().getOrDefault("EXPERIMENT_RAW", "false"));
		boolean rawTransactions = Boolean.parseBoolean(System.getenv().getOrDefault("EXPERIMENT_TXN", "true"));
		String rawWriteConcern = System.getenv().getOrDefault("EXPERIMENT_TXN_WC", "majority");
		boolean threadDump = Boolean.parseBoolean(System.getenv().getOrDefault("EXPERIMENT_THREAD_DUMP", "false"));
		warmupMs = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_WARMUP_MS", "30000"));
		measureMs = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_MEASURE_MS", "2000"));
		reps = Integer.parseInt(System.getenv().getOrDefault("EXPERIMENT_REPS", "5"));
		boolean discardDownstream = downstreamMode.equals("discard");
		boolean dropEvents = receiverMode.equals("drop");

		Counters counters = new Counters();
		MainDriver.setProbes(TestProbes.noop().withListenerFactory(
			listener -> new TimingListener(listener, counters, dropEvents)));
		try {
			driverFactory = customDriverFactory(testInfo, counters, discardDownstream);
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName("serial-fraction"),
				TestEntity.class,
				this::manyEntryInitialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			Refs refs = bosk.buildReferences(Refs.class);
			bosk.driver().flush();

			int rootDocumentBytes;
			{
				BsonDocument sizeDocument = mongoService.client()
					.getDatabase(driverSettings.database())
					.getCollection(driverSettings.collection(), BsonDocument.class)
					.aggregate(List.of(
						new BsonDocument("$match", new BsonDocument("_id", new BsonString("|"))),
						new BsonDocument("$project", new BsonDocument("size",
							new BsonDocument("$bsonSize", new BsonString("$$ROOT"))))))
					.first();
				rootDocumentBytes = sizeDocument == null ? -1 : sizeDocument.getInt32("size").getValue();
			}

			// Warm up the change stream.
			if (!dropEvents) {
				bosk.driver().submitReplacement(
					refs.catalog().then(Identifier.from("e0")).then(String.class, "string"), "warmup");
				bosk.driver().flush();
			}

			AtomicBoolean stop = new AtomicBoolean(false);
			AtomicLong updateCount = new AtomicLong();
			CountDownLatch start = new CountDownLatch(1);
			AtomicInteger failures = new AtomicInteger();
			AtomicReference<Throwable> firstFailure = new AtomicReference<>();
			List<Thread> workers = new ArrayList<>();
			MongoClient rawClient = raw ? mongoService.client() : null;
			MongoCollection<BsonDocument> rawCollection = raw
				? rawClient
					.getDatabase(driverSettings.database())
					.getCollection(driverSettings.collection(), BsonDocument.class)
				: null;
			for (int t = 0; t < experimentThreads; t++) {
				Identifier id = Identifier.from("e" + (t % catalogSize));
				Reference<String> field = refs.catalog().then(id).then(String.class, "string");
				Reference<TestEntity> entry = refs.catalog().then(id);
				TestEntity baseValue = TestEntity.empty(id, refs.childCatalog(id));
				String documentId = "|catalog|" + id;
				int threadIndex = t;
				workers.add(new Thread(() -> {
					try {
						start.await();
						for (long i = 0; !stop.get(); i++) {
							String value = "value-" + threadIndex + "-" + i;
							if (raw) {
								if (rawTransactions) {
									rawUpdate(rawClient, rawCollection, documentId, value, rawWriteConcern);
								} else {
									rawNonTransactionalUpdate(rawCollection, documentId, value, rawWriteConcern);
								}
							} else if (mode.equals("replace")) {
								bosk.driver().submitReplacement(entry, baseValue.withString(value));
							} else {
								bosk.driver().submitReplacement(field, value);
							}
							updateCount.incrementAndGet();
						}
					} catch (Throwable e) {
						failures.incrementAndGet();
						firstFailure.compareAndSet(null, e);
					}
				}, "experiment-worker-" + t));
			}
			workers.forEach(Thread::start);
			start.countDown();

			Thread.sleep(warmupMs);

			// Measure deltas from the end of the warm-up.
			long cumUpdatesBefore = updateCount.get();
			long cumEventsBefore = counters.events.sum();
			double[] ratesPerRep = new double[reps];
			double[] eventRatesPerRep = new double[reps];
			double[] busyPerRep = new double[reps];
			double[] nsPerEventPerRep = new double[reps];
			double[] nsPerDownstreamPerRep = new double[reps];
			double[] downstreamCallsPerEventPerRep = new double[reps];
			long[] lagPerRep = new long[reps];
			for (int rep = 0; rep < reps; rep++) {
				long startNanos = System.nanoTime();
				long updatesBefore = updateCount.get();
				long eventsBefore = counters.events.sum();
				long eventNanosBefore = counters.eventNanos.sum();
				long downstreamCallsBefore = counters.downstreamCalls.sum();
				long downstreamNanosBefore = counters.downstreamNanos.sum();
				Thread.sleep(measureMs);
				long endNanos = System.nanoTime();
				long updates = updateCount.get() - updatesBefore;
				long events = counters.events.sum() - eventsBefore;
				long eventNanos = counters.eventNanos.sum() - eventNanosBefore;
				long downstreamCalls = counters.downstreamCalls.sum() - downstreamCallsBefore;
				long downstreamNanos = counters.downstreamNanos.sum() - downstreamNanosBefore;
				double seconds = (endNanos - startNanos) / 1e9;
				assertTrue(updates > 0, "Measurement window must contain updates");
				ratesPerRep[rep] = updates / seconds;
				eventRatesPerRep[rep] = events / seconds;
				busyPerRep[rep] = (double) eventNanos / (endNanos - startNanos);
				nsPerEventPerRep[rep] = events == 0 ? 0 : (double) eventNanos / events;
				nsPerDownstreamPerRep[rep] = downstreamCalls == 0 ? 0 : (double) downstreamNanos / downstreamCalls;
				downstreamCallsPerEventPerRep[rep] = events == 0 ? 0 : (double) downstreamCalls / events;
				lagPerRep[rep] = (updateCount.get() - cumUpdatesBefore) - (counters.events.sum() - cumEventsBefore);
			}

			stop.set(true);
			for (Thread worker : workers) {
				worker.join(600_000);
				assertFalse(worker.isAlive(), "Update thread should finish");
			}
			if (!dropEvents) {
				bosk.driver().flush();
			}

			assertEquals(0, failures.get(),
				"Updates should not fail: " + firstFailure.get());

			if (threadDump) {
				System.out.println("=== THREAD DUMP AT PLATEAU ===");
				Thread.getAllStackTraces().forEach((thread, stack) -> {
					System.out.printf("%s [%s]%n", thread.getName(), thread.getState());
					for (StackTraceElement element : stack) {
						System.out.println("\tat " + element);
					}
				});
			}

			System.out.printf("SERIAL mode=%s threads=%d catalogSize=%d rootBytes=%d downstream=%s receiver=%s raw=%s txn=%s wc=%s "
					+ "trimmedMeanUpdatesPerSec=%.1f trimmedMeanEventsPerSec=%.1f trimmedMeanEventBusy=%.3f "
					+ "trimmedMeanNsPerEvent=%.0f trimmedMeanNsPerDownstream=%.0f trimmedMeanDownstreamCallsPerEvent=%.3f "
					+ "maxLag=%d rates=[%s] eventRates=[%s] busy=[%s] lag=[%s] warmupMs=%d measureMs=%d%n",
				mode, experimentThreads, catalogSize, rootDocumentBytes, downstreamMode, receiverMode,
				raw, raw ? rawTransactions : "-", raw ? rawWriteConcern : "-",
				trimmedMean(ratesPerRep), trimmedMean(eventRatesPerRep), trimmedMean(busyPerRep),
				trimmedMean(nsPerEventPerRep), trimmedMean(nsPerDownstreamPerRep), trimmedMean(downstreamCallsPerEventPerRep),
				max(lagPerRep),
				format(ratesPerRep), format(eventRatesPerRep), format(busyPerRep), formatLong(lagPerRep),
				warmupMs, measureMs);
		} finally {
			MainDriver.resetProbes();
		}
	}

	private <E extends works.bosk.Entity> DriverFactory<E> customDriverFactory(TestInfo testInfo, Counters counters, boolean discardDownstream) {
		DriverFactory<E> mongoDriverFactory = (boskInfo, downstream) -> {
			ForwardingDriver timingDownstream = new TimingDriver(downstream, counters, discardDownstream);
			MongoDriver driver = MongoDriver.<E>factory(
				mongoService.clientSettings(testInfo),
				driverSettings,
				new BsonSerializer()
			).build(boskInfo, timingDownstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};
		return DriverStack.of(
			BoskLogFilter.withController(logController),
			mongoDriverFactory
		);
	}

	private static void rawUpdate(MongoClient client, MongoCollection<BsonDocument> collection, String documentId, String value, String writeConcern) {
		BsonDocument filter = new BsonDocument("_id", new BsonString(documentId));
		BsonDocument update = rawUpdateDocument(value);
		TransactionOptions options = switch (writeConcern) {
			case "w1" -> TransactionOptions.builder().writeConcern(WriteConcern.W1).build();
			case "default" -> TransactionOptions.builder().build();
			default -> TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build();
		};
		try (ClientSession session = client.startSession()) {
			session.withTransaction(() -> {
				collection.updateOne(session, filter, update);
				return null;
			}, options);
		}
	}

	private static void rawNonTransactionalUpdate(MongoCollection<BsonDocument> collection, String documentId, String value, String writeConcern) {
		BsonDocument filter = new BsonDocument("_id", new BsonString(documentId));
		MongoCollection<BsonDocument> target = switch (writeConcern) {
			case "w1" -> collection.withWriteConcern(WriteConcern.W1);
			case "default" -> collection;
			default -> collection.withWriteConcern(WriteConcern.MAJORITY);
		};
		target.updateOne(filter, rawUpdateDocument(value));
	}

	private static BsonDocument rawUpdateDocument(String value) {
		return new BsonDocument("$inc", new BsonDocument("revision", new BsonInt64(1)))
			.append("$set", new BsonDocument("state.string", new BsonString(value)));
	}

	private TestEntity manyEntryInitialState(Bosk<TestEntity> bosk) throws works.bosk.exceptions.InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Catalog<TestEntity> catalog = Catalog.empty();
		for (int i = 0; i < catalogSize; i++) {
			Identifier id = Identifier.from("e" + i);
			catalog = catalog.with(TestEntity.empty(id, refs.childCatalog(id)));
		}
		return initialRootWithEmptyCatalog(bosk).withCatalog(catalog);
	}

	/**
	 * The mean of the middle three measurements when there are at least five,
	 * discarding the extreme values on each end, matching the protocol used for
	 * the {@code ParallelUpdateExperiment} results.
	 */
	private static double trimmedMean(double[] values) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int first = sorted.length >= 5 ? 1 : 0;
		int last = sorted.length >= 5 ? sorted.length - 1 : sorted.length;
		double sum = 0;
		for (int i = first; i < last; i++) {
			sum += sorted[i];
		}
		return sum / (last - first);
	}

	private static long max(long[] values) {
		long result = Long.MIN_VALUE;
		for (long value : values) {
			result = Math.max(result, value);
		}
		return result;
	}

	private static String format(double[] values) {
		StringBuilder result = new StringBuilder();
		for (double value : values) {
			if (result.length() > 0) {
				result.append(',');
			}
			result.append(String.format("%.3f", value));
		}
		return result.toString();
	}

	private static String formatLong(long[] values) {
		StringBuilder result = new StringBuilder();
		for (long value : values) {
			if (result.length() > 0) {
				result.append(',');
			}
			result.append(value);
		}
		return result.toString();
	}

	/**
	 * Times the receiver's event handling. In {@code drop} mode, the event is
	 * counted but not handed to the format driver, which measures the raw drain
	 * rate.
	 */
	static final class TimingListener implements ChangeListener {
		private final ChangeListener downstream;
		private final Counters counters;
		private final boolean dropEvents;

		TimingListener(ChangeListener downstream, Counters counters, boolean dropEvents) {
			this.downstream = downstream;
			this.counters = counters;
			this.dropEvents = dropEvents;
		}

		@Override
		public void onConnectionSucceeded() throws
			FailedMongoClientSessionException,
			InitialStateException,
			InterruptedException,
			InvalidCollectionContentsException,
			IOException,
			TimeoutException,
			UnrecognizedFormatException
		{
			downstream.onConnectionSucceeded();
		}

		@Override
		public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, FormatChangedException {
			counters.events.increment();
			long start = System.nanoTime();
			try {
				if (!dropEvents) {
					downstream.onEvent(event);
				}
			} finally {
				counters.eventNanos.add(System.nanoTime() - start);
			}
		}

		@Override
		public void onConnectionFailed(Exception cause) throws DownstreamInitialStateException {
			downstream.onConnectionFailed(cause);
		}

		@Override
		public void onDisconnect(Throwable e) {
			downstream.onDisconnect(e);
		}
	}

	/**
	 * Times the downstream driver's update methods. In {@code discard} mode,
	 * the update is not forwarded, so the local state never changes, which
	 * isolates the cost of the local apply.
	 */
	static final class TimingDriver extends ForwardingDriver {
		private final Counters counters;
		private final boolean discard;

		TimingDriver(works.bosk.BoskDriver downstream, Counters counters, boolean discard) {
			super(downstream);
			this.counters = counters;
			this.discard = discard;
		}

		@Override
		public <T> void submitReplacement(Reference<T> target, T newValue) {
			time(() -> downstream.submitReplacement(target, newValue));
		}

		@Override
		public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
			time(() -> downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue));
		}

		@Override
		public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
			time(() -> downstream.submitConditionalCreation(target, newValue));
		}

		@Override
		public <T> void submitDeletion(Reference<T> target) {
			time(() -> downstream.submitDeletion(target));
		}

		@Override
		public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
			time(() -> downstream.submitConditionalDeletion(target, precondition, requiredValue));
		}

		private void time(Runnable update) {
			counters.downstreamCalls.increment();
			long start = System.nanoTime();
			try {
				if (!discard) {
					update.run();
				}
			} finally {
				counters.downstreamNanos.add(System.nanoTime() - start);
			}
		}
	}

	static final class Counters {
		final LongAdder events = new LongAdder();
		final LongAdder eventNanos = new LongAdder();
		final LongAdder downstreamCalls = new LongAdder();
		final LongAdder downstreamNanos = new LongAdder();
	}

	private int experimentThreads;
	private int catalogSize;
	private String mode;
	private int warmupMs;
	private int measureMs;
	private int reps;
}
