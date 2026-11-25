package works.bosk.drivers.mongo.internal;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;
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
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * The {@code writeId} roles are what the receiver uses to assemble transactions,
 * so check them directly: exactly one {@code last:} per transaction, a fresh
 * {@code part:} for an unchanged sub-part, and a reconnect for a malformed role.
 */
@ReplayLogsOnFailure
@InjectFields
class WriteIdProtocolTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"WriteIdProtocolTest",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog", "/sideTable"))
				.timescaleMS(LONG_TIMESCALE)
				.database("WriteIdProtocolTest")));
	}

	private final List<ChangeStreamDocument<BsonDocument>> events = new CopyOnWriteArrayList<>();
	private final List<Throwable> disconnections = new CopyOnWriteArrayList<>();

	@AfterEach
	void resetProbes() {
		MainDriver.resetProbes();
	}

	@Test
	void oneLastWritePerTransaction() throws Exception {
		Bosk<TestEntity> bosk = newRecordingBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		events.clear();

		bosk.driver().submitReplacement(refs.catalog(), Catalog.of(
			TestEntity.empty(entity123, refs.childCatalog(entity123)).withString("first"),
			TestEntity.empty(entity124, refs.childCatalog(entity124)).withString("second")));
		bosk.driver().flush();

		List<WriteRole> roles = events.stream()
			.map(WriteIdProtocolTest::roleOf)
			.filter(Objects::nonNull)
			.toList();
		assertEquals(1, roles.stream().filter(role -> role == WriteRole.LAST).count(),
			"Exactly one last write per transaction: " + describe(events));
		assertTrue(roles.contains(WriteRole.PART),
			"Sub-part writes must carry part: markers: " + describe(events));
		assertEquals(WriteRole.LAST, roles.getLast(),
			"The last write must be the last marker in the transaction: " + describe(events));
	}

	@Test
	void unchangedSubPartStillProducesAnEvent() throws Exception {
		Bosk<TestEntity> bosk = newRecordingBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		Catalog<TestEntity> current;
		try (var _ = bosk.readSession()) {
			current = refs.catalog().value();
		}
		events.clear();

		bosk.driver().submitReplacement(refs.catalog(), current);
		bosk.driver().flush();

		assertTrue(events.stream().anyMatch(event ->
				event.getOperationType() == OperationType.REPLACE
					&& "|catalog|123".equals(documentIdOf(event))
					&& roleOf(event) == WriteRole.PART),
			"An unchanged sub-part must still produce a fresh part: event: " + describe(events));
	}

	@Test
	void diagnosticsGoOnTopmostDocumentNotRoot() throws Exception {
		Bosk<TestEntity> bosk = newRecordingBosk();
		Refs refs = bosk.buildReferences(Refs.class);

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

	@Test
	void subDocumentUpdate_writesOnlyItsOwnDocument() throws Exception {
		Bosk<TestEntity> bosk = newRecordingBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		events.clear();

		bosk.driver().submitReplacement(
			refs.catalog().then(entity123).then(String.class, "string"), "changed");
		bosk.driver().flush();

		assertEquals(Set.of("|catalog|123"), stateDocumentIds(events),
			"A scalar sub-field update must write only the document that contains it: " + describe(events));
	}

	@Test
	void concurrentUpdatesToDifferentGraftDocuments_bothSucceed() throws Exception {
		Bosk<TestEntity> bosk = newRecordingBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		int updatesPerThread = 20;
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger failures = new AtomicInteger();
		AtomicReference<Throwable> firstFailure = new AtomicReference<>();
		List<Thread> threads = new ArrayList<>();
		for (Identifier id : List.of(entity123, entity124)) {
			Reference<String> field = refs.catalog().then(id).then(String.class, "string");
			threads.add(Thread.ofPlatform().start(() -> {
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
		start.countDown();
		for (Thread thread : threads) {
			thread.join(60_000);
			assertFalse(thread.isAlive(), "Update thread should finish");
		}
		bosk.driver().flush();
		assertEquals(0, failures.get(),
			"Concurrent updates to different graft documents must not conflict: " + firstFailure.get());
	}

	@Test
	void updatesToDifferentGraftDocuments_writeDisjointDocuments() throws Exception {
		Bosk<TestEntity> bosk = newRecordingBosk();
		Refs refs = bosk.buildReferences(Refs.class);

		events.clear();
		bosk.driver().submitReplacement(
			refs.catalog().then(entity123).then(String.class, "string"), "first");
		bosk.driver().flush();
		Set<String> firstIds = stateDocumentIds(events);

		events.clear();
		bosk.driver().submitReplacement(
			refs.catalog().then(entity124).then(String.class, "string"), "second");
		bosk.driver().flush();
		Set<String> secondIds = stateDocumentIds(events);

		assertEquals(Set.of("|catalog|123"), firstIds,
			"An update to one graft entry must write only that entry: " + describe(events));
		assertEquals(Set.of("|catalog|124"), secondIds,
			"An update to the other graft entry must write only that entry: " + describe(events));
	}

	@Test
	void creatingEntry_writesDocumentThenParentStub() throws Exception {
		Bosk<TestEntity> bosk = newRecordingBosk();
		Refs refs = bosk.buildReferences(Refs.class);
		Identifier newEntity = Identifier.from("125");
		events.clear();

		bosk.driver().submitConditionalCreation(
			refs.catalog().then(newEntity),
			TestEntity.empty(newEntity, refs.childCatalog(newEntity)));
		bosk.driver().flush();

		List<String> order = events.stream()
			.map(WriteIdProtocolTest::documentIdOf)
			.filter(id -> "|catalog|125".equals(id) || "|".equals(id))
			.toList();
		assertEquals(List.of("|catalog|125", "|"), order,
			"The new entry must be written before the stub in the parent document: " + describe(events));
		assertTrue(events.stream()
				.filter(event -> "|".equals(documentIdOf(event)))
				.allMatch(event -> roleOf(event) == WriteRole.LAST),
			"The parent stub must be the transaction's last write: " + describe(events));
	}

	@Test
	void ignoredEventAfterLastWriteDoesNotBlockFlush() throws Exception {
		Bosk<TestEntity> bosk = newRecordingBosk();

		try (var session = mongoService.client().startSession()) {
			session.startTransaction();
			MongoCollection<BsonDocument> collection = collection();
			collection.updateOne(session, rootDocumentsFilter(),
				new BsonDocument("$set", new BsonDocument()
					.append("state.string", new BsonString("edited in a transaction"))
					.append(DocumentFields.writeId.name(), WriteRole.LAST.newWriteId())));
			collection.updateOne(session, rootDocumentsFilter(),
				new BsonDocument("$set", new BsonDocument(
					DocumentFields.lockId.name(), new BsonObjectId(new ObjectId()))));
			session.commitTransaction();
		}

		bosk.driver().flush();
		assertEquals("edited in a transaction", rootString(bosk),
			"The transaction's state must be applied");
		assertTrue(disconnections.isEmpty(),
			"A trailing ignored event must not need a reconnect: " + disconnections);
	}

	@Test
	void partMarkedUpdate_reconnectsAndReloads() throws Exception {
		assertMalformedWriteReconnectsAndReloads(
			"A sub-part write must be a REPLACE",
			(session, collection) -> collection.updateOne(session, rootDocumentsFilter(),
				new BsonDocument("$set", new BsonDocument()
					.append("state.string", new BsonString("part-marked update"))
					.append(DocumentFields.writeId.name(), WriteRole.PART.newWriteId()))),
			"part-marked update");
	}

	@Test
	void rootReplaceWithoutWriteId_reconnectsAndReloads() throws Exception {
		assertMalformedWriteReconnectsAndReloads(
			"Root replace without a writeId",
			(session, collection) -> {
				BsonDocument document = stateDocument("|");
				stateOf(document).put("string", new BsonString("replace without a marker"));
				document.remove(DocumentFields.writeId.name());
				collection.replaceOne(session, rootDocumentsFilter(), document);
			},
			"replace without a marker");
	}

	@Test
	void unrecognizedWriteId_reconnectsAndReloads() throws Exception {
		assertMalformedWriteReconnectsAndReloads(
			"Unrecognized writeId",
			(session, collection) -> collection.updateOne(session, rootDocumentsFilter(),
				new BsonDocument("$set", new BsonDocument()
					.append("state.string", new BsonString("unrecognized marker"))
					.append(DocumentFields.writeId.name(),
						new BsonString("bogus:" + new ObjectId().toHexString())))),
			"unrecognized marker");
	}

	private void assertMalformedWriteReconnectsAndReloads(String messagePart, MalformedWrite write, String expectedString) throws Exception {
		Bosk<TestEntity> bosk = newRecordingBosk();
		try (var session = mongoService.client().startSession()) {
			session.startTransaction();
			write.apply(session, collection());
			session.commitTransaction();
		}

		assertEventually(
			() -> disconnections.stream().anyMatch(t -> String.valueOf(t).contains(messagePart)),
			"A malformed writeId role must disconnect the receiver: " + disconnections);
		assertEventually(
			() -> expectedString.equals(rootString(bosk)),
			"The reconnect must reload the operator's edit");
	}

	@FunctionalInterface
	interface MalformedWrite {
		void apply(ClientSession session, MongoCollection<BsonDocument> collection);
	}

	private static BsonDocument stateOf(BsonDocument document) {
		return document.getDocument("state");
	}

	private Bosk<TestEntity> newRecordingBosk() throws InvalidTypeException, IOException, InterruptedException {
		MainDriver.setProbes(TestProbes.noop().withListenerFactory(downstream ->
			new ForwardingChangeListener(downstream) {
				@Override
				public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, CollectionRewrittenException {
					events.add(event);
					super.onEvent(event);
				}

				@Override
				public void onDisconnect(Throwable e) {
					disconnections.add(e);
					super.onDisconnect(e);
				}
			}));
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		bosk.driver().flush();
		events.clear();
		disconnections.clear();
		return bosk;
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

	private static String rootString(Bosk<TestEntity> bosk) {
		try (var _ = bosk.readSession()) {
			Reference<String> rootString = bosk.rootReference().then(String.class, "string");
			return rootString.value();
		} catch (InvalidTypeException e) {
			throw new AssertionError(e);
		}
	}

	private static void assertEventually(BooleanSupplier condition, String description) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(10);
		}
		assertTrue(condition.getAsBoolean(), description);
	}

	private static @Nullable WriteRole roleOf(ChangeStreamDocument<BsonDocument> event) {
		BsonValue value = switch (event.getOperationType()) {
			case UPDATE -> event.getUpdateDescription() == null
				? null
				: event.getUpdateDescription().getUpdatedFields().get(DocumentFields.writeId.name());
			case INSERT, REPLACE -> event.getFullDocument() == null
				? null
				: event.getFullDocument().get(DocumentFields.writeId.name());
			default -> null;
		};
		return WriteRole.of(value).orElse(null);
	}

	private static Set<String> stateDocumentIds(List<ChangeStreamDocument<BsonDocument>> events) {
		return events.stream()
			.map(WriteIdProtocolTest::documentIdOf)
			.filter(id -> id != null && id.startsWith("|"))
			.collect(Collectors.toSet());
	}

	private static @Nullable String documentIdOf(ChangeStreamDocument<BsonDocument> event) {
		if (event.getDocumentKey() == null) {
			return null;
		}
		BsonValue id = event.getDocumentKey().get("_id");
		return id instanceof BsonString s ? s.getValue() : null;
	}

	private static String describe(List<ChangeStreamDocument<BsonDocument>> events) {
		return events.stream()
			.map(event -> event.getOperationType() + " " + documentIdOf(event) + " " + roleOf(event))
			.toList()
			.toString();
	}
}
