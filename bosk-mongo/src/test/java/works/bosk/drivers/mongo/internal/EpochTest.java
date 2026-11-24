package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * The epoch changes when the collection is initialized or refurbished into
 * another format. It is recorded in the manifest and on every state document,
 * is preserved by a refurbish that doesn't change the format, and is replaced
 * by one that does.
 */
@ReplayLogsOnFailure
@InjectFields
class EpochTest extends AbstractMongoDriverTest {
	private TestInfo testInfo;

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				MongoDriverSettings.DatabaseFormat.SEQUOIA,
				PandoFormat.oneBigDocument()
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		);
	}

	@BeforeEach
	void captureTestInfo(TestInfo testInfo) {
		this.testInfo = testInfo;
	}

	@Test
	void initialState_recordsEpochInManifestAndDocuments() {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		assertEquals(epochOf(bosk), epochInRootDocument(driverSettings.preferredDatabaseFormat()),
			"The root document should carry the manifest's epoch");
	}

	@Test
	void sameFormatRefurbish_keepsEpoch() throws Exception {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		Identifier before = epochOf(bosk);
		Identifier beforeInDocument = epochInRootDocument(driverSettings.preferredDatabaseFormat());
		bosk.getDriver(MongoDriver.class).refurbish();
		assertEquals(before, epochOf(bosk), "Refurbishing to the same format should keep the epoch");
		assertEquals(beforeInDocument, epochInRootDocument(driverSettings.preferredDatabaseFormat()),
			"The state documents should keep the same epoch");
	}

	@Test
	void formatChangingRefurbish_mintsNewEpoch() throws Exception {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		Identifier before = epochOf(bosk);

		MongoDriverSettings refurbishedSettings = driverSettings.toBuilder()
			.preferredDatabaseFormat(differentFormat(driverSettings.preferredDatabaseFormat()))
			.build();
		Bosk<TestEntity> refurbisher = newBosk(refurbishedSettings, "refurbisher");
		refurbisher.getDriver(MongoDriver.class).refurbish();

		assertNotEquals(before, epochOf(refurbisher), "Changing the format should mint a new epoch");
		assertEquals(epochOf(refurbisher), epochInRootDocument(refurbishedSettings.preferredDatabaseFormat()),
			"The documents should carry the new epoch");
	}

	@Test
	void mismatchedEpoch_failsToLoad() throws Exception {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		Identifier original = epochOf(bosk);

		// Stop this bosk before corrupting the collection: otherwise its receiver
		// spends the rest of the test failing to load the corrupted contents.
		bosk.getDriver(MongoDriver.class).close();

		// Corrupt one document, as something outside Bosk might.
		collection().updateOne(
			new BsonDocument("_id", new BsonString(rootDocumentId(driverSettings.preferredDatabaseFormat()))),
			new BsonDocument("$set", new BsonDocument(DocumentFields.epoch.name(), new BsonString("not-the-epoch")))
		);

		// Load with a format driver directly, without starting another bosk.
		SessionCollection sessionCollection = SessionCollection.of(collection(), mongoService.client());
		FormatDriver<TestEntity> driver = newFormatDriver(bosk, sessionCollection, original);
		InvalidCollectionContentsException e;
		try (var _ = sessionCollection.newReadOnlySession()) {
			e = assertThrows(InvalidCollectionContentsException.class, driver::loadAllState);
		}
		String message = e.getMessage();
		assertTrue(message.contains("Epoch mismatch on state document"), "Message should describe the mismatch: " + message);
		assertTrue(message.contains(original.toString()), "Message should name the expected epoch: " + message);
		assertTrue(message.contains("not-the-epoch"), "Message should name the found epoch: " + message);
	}

	@Test
	void updateThatUnsetsEpoch_isRejected() throws Exception {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		Identifier epoch = epochOf(bosk);
		bosk.getDriver(MongoDriver.class).close();

		SessionCollection sessionCollection = SessionCollection.of(collection(), mongoService.client());
		AbstractFormatDriver<TestEntity> driver = newFormatDriver(bosk, sessionCollection, epoch);

		ChangeStreamDocument<BsonDocument> event = new ChangeStreamDocument<>(
			"update",
			new BsonDocument(),
			null,
			null,
			null,
			null,
			null,
			new BsonDocument("_id", new BsonString(rootDocumentId(driverSettings.preferredDatabaseFormat()))),
			null,
			new UpdateDescription(List.of(DocumentFields.epoch.name()), new BsonDocument()),
			null,
			null,
			null,
			null,
			null
		);

		assertThrows(UnprocessableEventException.class, () -> driver.validateEventEpoch(event));
	}

	@Test
	void replaceWithForeignEpoch_isRejected() throws Exception {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		Identifier epoch = epochOf(bosk);
		bosk.getDriver(MongoDriver.class).close();

		SessionCollection sessionCollection = SessionCollection.of(collection(), mongoService.client());
		AbstractFormatDriver<TestEntity> driver = newFormatDriver(bosk, sessionCollection, epoch);

		BsonString documentId = new BsonString(rootDocumentId(driverSettings.preferredDatabaseFormat()));
		BsonDocument foreignDocument = new BsonDocument("_id", documentId)
			.append(DocumentFields.epoch.name(), new BsonString("not-the-epoch"));
		ChangeStreamDocument<BsonDocument> event = new ChangeStreamDocument<>(
			"replace",
			new BsonDocument(),
			null,
			null,
			null,
			foreignDocument,
			null,
			new BsonDocument("_id", documentId),
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);

		assertThrows(CollectionRewrittenException.class, () -> driver.validateEventEpoch(event));
	}

	@Test
	void refurbishRacingAnotherReplicasFormatChange_keepsManifestAndDocumentsAgreeing() throws Exception {
		AtomicBoolean armed = new AtomicBoolean(false);
		CountDownLatch reloaded = new CountDownLatch(1);
		AtomicReference<Bosk<TestEntity>> refurbisher = new AtomicReference<>();

		MainDriver.setProbes(TestProbes.noop()
			.withListenerFactory(listener -> new ForwardingChangeListener(listener) {
				@Override
				public void onConnectionSucceeded() throws UnrecognizedFormatException, InterruptedException, IOException, TimeoutException, InitialStateException, FailedMongoClientSessionException, InvalidCollectionContentsException {
					super.onConnectionSucceeded();
					if (armed.get()) {
						reloaded.countDown();
					}
				}
			})
			.withBeforeRefurbishLoad(() -> {
				if (armed.compareAndSet(false, true)) {
					try {
						// Another replica refurbishes to the other format while this
						// refurbish has already captured the driver it started with.
						refurbisher.get().getDriver(MongoDriver.class).refurbish();
						assertTrue(reloaded.await(30, SECONDS),
							"The victim should reload after the other replica's refurbish");
					} catch (IOException | InterruptedException e) {
						throw new AssertionError(e);
					}
				}
			}));

		// The victim goes first so it initializes the collection in its own format;
		// the refurbisher, constructed without probes, then adopts that format.
		Bosk<TestEntity> victim;
		try {
			victim = newBosk(driverSettings, "victim");
		} finally {
			MainDriver.resetProbes();
		}
		refurbisher.set(newBosk(differentSettings(), "refurbisher"));

		victim.getDriver(MongoDriver.class).refurbish();

		Manifest manifest = victim.getDriver(MainDriver.class).loadManifestInfo().manifest();
		assertEquals(driverSettings.preferredDatabaseFormat(), manifest.format(),
			"The refurbish should leave the manifest describing the preferred format");
		assertEquals(manifest.epoch(), epochInRootDocument(driverSettings.preferredDatabaseFormat()),
			"The manifest and the state documents should carry the same epoch");
	}

	private MongoDriverSettings differentSettings() {
		return driverSettings.toBuilder()
			.preferredDatabaseFormat(differentFormat(driverSettings.preferredDatabaseFormat()))
			.build();
	}

	private AbstractFormatDriver<TestEntity> newFormatDriver(Bosk<TestEntity> bosk, SessionCollection sessionCollection, Identifier epoch) {
		BsonSerializer serializer = new BsonSerializer();
		Duration flushTimeout = Duration.ofSeconds(1);
		return switch (driverSettings.preferredDatabaseFormat()) {
			case MongoDriverSettings.SequoiaFormat _ -> new SequoiaFormatDriver<>(
				bosk, sessionCollection, driverSettings, serializer, flushTimeout, bosk.driver(), epoch);
			case PandoFormat pandoFormat -> new PandoFormatDriver<>(
				bosk, sessionCollection, driverSettings, pandoFormat, serializer, flushTimeout, bosk.driver(), epoch);
		};
	}

	private Bosk<TestEntity> newBosk(MongoDriverSettings settings, String name) {
		DriverFactory<TestEntity> factory = (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<TestEntity>factory(
				mongoService.clientSettings(testInfo),
				settings,
				new BsonSerializer())
				.build(boskInfo, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};
		return new Bosk<>(
			boskName(name),
			TestEntity.class,
			AbstractMongoDriverTest::initialRoot,
			BoskConfig.<TestEntity>builder().driverFactory(factory).build());
	}

	private Identifier epochOf(Bosk<TestEntity> bosk) {
		try {
			return bosk.getDriver(MainDriver.class).loadManifestInfo().manifest().epoch();
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private Identifier epochInRootDocument(MongoDriverSettings.DatabaseFormat format) {
		BsonDocument document = collection()
			.find(new BsonDocument("_id", new BsonString(rootDocumentId(format))))
			.first();
		return Identifier.from(document.getString(DocumentFields.epoch.name()).getValue());
	}


	private MongoCollection<BsonDocument> collection() {
		return mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
	}

	private static String rootDocumentId(MongoDriverSettings.DatabaseFormat format) {
		return format instanceof PandoFormat? "|": SequoiaFormatDriver.DOCUMENT_ID.getValue();
	}

	private static MongoDriverSettings.DatabaseFormat differentFormat(MongoDriverSettings.DatabaseFormat current) {
		return current instanceof PandoFormat? MongoDriverSettings.DatabaseFormat.SEQUOIA: PandoFormat.withGraftPoints("/catalog");
	}

}
