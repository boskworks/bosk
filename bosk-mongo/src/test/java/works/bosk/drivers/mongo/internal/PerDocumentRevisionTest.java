package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.libtesting.BlockingGate;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Verifies the per-document revision design: an update bumps only the topmost
 * document it changes, so updates to different graft documents write disjoint
 * sets of documents and do not contend on the root.
 */
@ReplayLogsOnFailure
@InjectFields
public class PerDocumentRevisionTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"PerDocumentRevisionTest",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog", "/sideTable"))
				.timescaleMS(LONG_TIMESCALE)
				.database("PerDocumentRevisionTest")));
	}

	@AfterEach
	void resetProbes() {
		MainDriver.resetProbes();
	}

	@Test
	void subDocumentUpdate_writesOnlyItsOwnDocument() throws Exception {
		RecordingWrites writes = new RecordingWrites();
		MainDriver.setProbes(TestProbes.noop().withWriteInterceptor(writes));
		Bosk<TestEntity> bosk = newBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		bosk.driver().flush();

		BsonInt64 rootRevisionBefore = rootRevision();
		writes.clear();

		bosk.driver().submitReplacement(
			refs.catalog().then(entity123).then(String.class, "string"), "changed");
		bosk.driver().flush();

		assertEquals(Set.of("|catalog|123"), Set.copyOf(writes.documentIds()),
			"A sub-document update must write only the sub-document");
		assertEquals(rootRevisionBefore, rootRevision(),
			"A sub-document update must not advance the root revision");
	}

	@Test
	void updatesToDifferentGraftDocuments_writeDisjointDocuments() throws Exception {
		RecordingWrites writes = new RecordingWrites();
		MainDriver.setProbes(TestProbes.noop().withWriteInterceptor(writes));
		Bosk<TestEntity> bosk = newBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		bosk.driver().flush();

		writes.clear();
		bosk.driver().submitReplacement(
			refs.catalog().then(entity123).then(String.class, "string"), "first");
		bosk.driver().flush();
		Set<String> firstWrites = Set.copyOf(writes.documentIds());

		writes.clear();
		bosk.driver().submitReplacement(
			refs.catalog().then(entity124).then(String.class, "string"), "second");
		bosk.driver().flush();
		Set<String> secondWrites = Set.copyOf(writes.documentIds());

		assertFalse(firstWrites.contains("|"), "The root must not be written: " + firstWrites);
		assertFalse(secondWrites.contains("|"), "The root must not be written: " + secondWrites);
		assertTrue(Collections.disjoint(firstWrites, secondWrites),
			"Concurrent updates to different graft documents must not share a document: "
				+ firstWrites + " and " + secondWrites);
	}

	@Test
	void concurrentUpdatesToDifferentGraftDocuments_bothSucceed() throws Exception {
		Bosk<TestEntity> bosk = newBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		bosk.driver().flush();

		int updatesPerThread = 20;
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger failures = new AtomicInteger();
		AtomicReference<Throwable> firstFailure = new AtomicReference<>();
		List<Thread> threads = new ArrayList<>();
		for (Identifier id: List.of(entity123, entity124)) {
			Reference<String> field = refs.catalog().then(id).then(String.class, "string");
			threads.add(new Thread(() -> {
				try {
					start.await();
					for (int i = 0; i < updatesPerThread; i++) {
						bosk.driver().submitReplacement(field, "value-" + i);
					}
				} catch (Throwable e) {
					failures.incrementAndGet();
					firstFailure.compareAndSet(null, e);
				}
			}));
		}
		threads.forEach(Thread::start);
		start.countDown();
		for (Thread thread: threads) {
			thread.join(60_000);
			assertFalse(thread.isAlive(), "Update thread should finish");
		}
		bosk.driver().flush();

		assertEquals(0, failures.get(),
			"Concurrent updates to different graft documents must not conflict: " + firstFailure.get());
	}

	@Test
	void recreatedDocument_changesIncarnationAndResetsRevision() throws Exception {
		Bosk<TestEntity> bosk = newBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		bosk.driver().flush();

		BsonDocument before = stateDocument("|catalog|123");
		String incarnationBefore = revisionOf(before).incarnation();

		// Replacing a whole graft document deletes and re-creates its document.
		TestEntity replacement = TestEntity.empty(entity123, refs.childCatalog(entity123))
			.withString("replacement");
		bosk.driver().submitReplacement(refs.catalog().then(entity123), replacement);
		bosk.driver().flush();

		BsonDocument after = stateDocument("|catalog|123");
		assertNotEquals(incarnationBefore, revisionOf(after).incarnation(),
			"Re-creating a document must give it a new incarnation");
		assertEquals(1, revisionOf(after).revision(),
			"Re-creating a document must reset its revision");
	}

	@Test
	void deletedAndRecreatedDocument_flushWaitsForNewIncarnation() throws Exception {
		BlockingGate gate = new BlockingGate("the re-created document's revision event");
		AtomicBoolean armed = new AtomicBoolean(false);
		MainDriver.setProbes(TestProbes.noop().withListenerFactory(downstream ->
			new ForwardingChangeListener(downstream) {
				@Override
				public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, FormatChangedException {
					if (armed.get() && isRevisionBearingUpdateOf(event, "|catalog|123")) {
						gate.signal();
						gate.awaitRelease(Duration.ofSeconds(60));
					}
					super.onEvent(event);
				}
			}));
		Bosk<TestEntity> bosk = newBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		bosk.driver().flush();

		// Give the old document a revision higher than the revision the new
		// incarnation will reach in this test.
		for (int i = 0; i < 3; i++) {
			bosk.driver().submitReplacement(
				refs.catalog().then(entity123).then(String.class, "string"), "old" + i);
		}
		bosk.driver().flush();
		assertTrue(revisionOf(stateDocument("|catalog|123")).revision() >= 3,
			"The old incarnation must have a revision higher than the new one will reach");

		// Delete the entry, then remove the orphan document as a hard delete
		// would, so the re-creation is a genuinely new lifetime.
		bosk.driver().submitDeletion(refs.catalog().then(entity123));
		bosk.driver().flush();
		collection().deleteOne(new BsonDocument("_id", new BsonString("|catalog|123")));

		// Re-create the entry and let the re-creation be applied.
		bosk.driver().submitReplacement(refs.catalog().then(entity123),
			TestEntity.empty(entity123, refs.childCatalog(entity123)).withString("new"));
		bosk.driver().flush();

		// The re-created document's next change is revision 2 of the new
		// incarnation, which is lower than the old incarnation's revision.
		armed.set(true);
		bosk.driver().submitReplacement(
			refs.catalog().then(entity123).then(String.class, "string"), "newer");
		gate.awaitSignal(Duration.ofSeconds(30));

		Thread flushThread = new Thread(() -> {
			try {
				bosk.driver().flush();
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		flushThread.start();
		flushThread.join(1000);
		assertTrue(flushThread.isAlive(),
			"Flush returned before the re-created document's revision was applied");

		gate.release();
		flushThread.join(60_000);
		assertFalse(flushThread.isAlive(), "Flush should finish once the event is processed");
	}

	private static boolean isRevisionBearingUpdateOf(ChangeStreamDocument<BsonDocument> event, String documentId) {
		return event.getOperationType() == OperationType.UPDATE
			&& documentId.equals(event.getDocumentKey().getString("_id").getValue())
			&& event.getUpdateDescription() != null
			&& event.getUpdateDescription().getUpdatedFields().containsKey(DocumentFields.revision.name());
	}

	@Test
	void flushReadsDocumentsBottomUp() throws Exception {
		List<String> readOrder = new CopyOnWriteArrayList<>();
		MainDriver.setProbes(TestProbes.noop().withFindInterceptor((filter, options, cursor) -> {
			if (isFlushRevisionRead(options)) {
				return new DocCursor() {
					@Override
					public boolean hasNext() {
						return cursor.hasNext();
					}

					@Override
					public BsonDocument next() {
						BsonDocument document = cursor.next();
						readOrder.add(document.getString("_id").getValue());
						return document;
					}

					@Override
					public void close() {
						cursor.close();
					}
				};
			} else {
				return cursor;
			}
		}));
		Bosk<TestEntity> bosk = newBosk();
		bosk.driver().flush();
		readOrder.clear();

		bosk.driver().flush();

		assertEquals(List.of("|catalog|124", "|catalog|123", "|"), readOrder,
			"The flush read must go bottom-up, with the root last");
	}

	private static boolean isFlushRevisionRead(ReadOptions options) {
		Bson projection = options.projection();
		if (projection == null) {
			return false;
		}
		BsonDocument projectionDocument = projection.toBsonDocument(
			BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
		return projectionDocument.containsKey(DocumentFields.incarnation.name());
	}

	@Test
	void everyStateDocument_hasRevisionAndIncarnation_butNoSentinel() throws Exception {
		newBosk().driver().flush();

		MongoCollection<BsonDocument> collection = collection();
		try (var cursor = collection.find().cursor()) {
			while (cursor.hasNext()) {
				BsonDocument document = cursor.next();
				String id = document.getString("_id").getValue();
				if (!id.startsWith("|")) {
					continue; // manifest
				}
				assertTrue(document.containsKey(DocumentFields.incarnation.name()),
					"State document " + id + " must have an incarnation: " + document);
				assertTrue(document.containsKey(DocumentFields.revision.name()),
					"State document " + id + " must have a revision: " + document);
				assertFalse(document.containsKey("sentinel"),
					"State document " + id + " must not have a sentinel: " + document);
			}
		}
	}

	@Test
	void transaction_hasExactlyOneRevisionBearingUpdate() throws Exception {
		List<RecordedEvent> events = new CopyOnWriteArrayList<>();
		AtomicBoolean recording = new AtomicBoolean(false);
		MainDriver.setProbes(TestProbes.noop().withListenerFactory(downstream -> new ForwardingChangeListener(downstream) {
			@Override
			public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, FormatChangedException {
				if (recording.get()) {
					events.add(RecordedEvent.of(event));
				}
				super.onEvent(event);
			}
		}));
		Bosk<TestEntity> bosk = newBosk();
		Refs refs = bosk.buildReferences(Refs.class);

		// Warm up so that no initialization events remain when recording starts.
		bosk.driver().submitReplacement(
			refs.catalog().then(entity123).then(String.class, "string"), "warmup");
		bosk.driver().flush();
		recording.set(true);

		// This re-creates both graft documents and bumps the root, all in one transaction.
		bosk.driver().submitReplacement(refs.catalog(), Catalog.of(
			TestEntity.empty(entity123, refs.childCatalog(entity123)).withString("first"),
			TestEntity.empty(entity124, refs.childCatalog(entity124)).withString("second")));
		bosk.driver().flush();

		List<RecordedEvent> revisionUpdates = events.stream()
			.filter(event -> event.operationType() == OperationType.UPDATE && event.hasRevision())
			.toList();
		assertEquals(1, revisionUpdates.size(),
			"A transaction must have exactly one revision-bearing UPDATE: " + events);
		assertEquals("|", revisionUpdates.getFirst().documentId(),
			"The revision-bearing UPDATE must be the topmost document");

		assertTrue(events.stream().anyMatch(event ->
				event.operationType() == OperationType.INSERT
					&& event.hasRevision()
					&& !event.documentId().equals("|")),
			"Sub-document inserts carry revisions, so finality must not be based on the revision alone: " + events);
	}

	@Test
	void diagnostics_goOnTopmostDocument_notRoot() throws Exception {
		Bosk<TestEntity> bosk = newBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		bosk.driver().flush();

		try (var _ = bosk.context().withAttribute("test", "sub-document")) {
			bosk.driver().submitReplacement(
				refs.catalog().then(entity123).then(String.class, "string"), "changed");
		}
		bosk.driver().flush();

		assertEquals("sub-document", diagnosticsOf(stateDocument("|catalog|123")),
			"The topmost document must carry the transaction's diagnostics");
		assertNull(diagnosticsOf(stateDocument("|")),
			"The root must not carry diagnostics for a sub-document update");
	}

	private Bosk<TestEntity> newBosk() throws InvalidTypeException {
		return new Bosk<>(
			boskName(),
			TestEntity.class,
			this::twoEntryInitialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
	}

	private TestEntity twoEntryInitialState(Bosk<TestEntity> bosk) throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		return initialRootWithEmptyCatalog(bosk)
			.withCatalog(Catalog.of(
				TestEntity.empty(entity123, refs.childCatalog(entity123)),
				TestEntity.empty(entity124, refs.childCatalog(entity124))));
	}

	private MongoCollection<BsonDocument> collection() {
		return mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
	}

	private BsonDocument stateDocument(String id) {
		try (var cursor = collection().find(new BsonDocument("_id", new BsonString(id))).cursor()) {
			return cursor.next();
		}
	}

	private BsonInt64 rootRevision() {
		return stateDocument("|").getInt64(DocumentFields.revision.name());
	}

	private static RevisionTuple revisionOf(BsonDocument document) {
		return RevisionTuple.from(document);
	}

	private static String diagnosticsOf(BsonDocument document) {
		BsonDocument diagnostics = document.getDocument(DocumentFields.diagnostics.name(), null);
		if (diagnostics == null) {
			return null;
		}
		BsonDocument attributes = diagnostics.getDocument("attributes", null);
		if (attributes == null) {
			return null;
		}
		BsonString value = attributes.getString("test", null);
		return value == null ? null : value.getValue();
	}

	/**
	 * A change stream event summarized for assertions about finality.
	 */
	private record RecordedEvent(OperationType operationType, String documentId, boolean hasRevision) {
		static RecordedEvent of(ChangeStreamDocument<BsonDocument> event) {
			boolean hasRevision;
			switch (event.getOperationType()) {
				case UPDATE -> {
					var description = event.getUpdateDescription();
					hasRevision = description != null
						&& description.getUpdatedFields().containsKey(DocumentFields.revision.name());
				}
				case INSERT, REPLACE -> hasRevision = event.getFullDocument() != null
					&& event.getFullDocument().containsKey(DocumentFields.revision.name());
				default -> hasRevision = false;
			}
			return new RecordedEvent(
				event.getOperationType(),
				event.getDocumentKey().getString("_id").getValue(),
				hasRevision);
		}
	}

	/**
	 * Records the {@code _id} of every document the driver writes.
	 */
	static final class RecordingWrites implements WriteInterceptor {
		private final List<String> documentIds = new CopyOnWriteArrayList<>();

		@Override
		public void beforeWrite(Bson filter) {
			BsonDocument filterDocument = filter.toBsonDocument(
				BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
			BsonValue id = filterDocument.get("_id");
			if (id instanceof BsonString s) {
				documentIds.add(s.getValue());
			}
		}

		List<String> documentIds() {
			return List.copyOf(documentIds);
		}

		void clear() {
			documentIds.clear();
		}
	}
}
