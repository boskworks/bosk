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
 * The format id identifies one instance of the database format. It is recorded
 * in the manifest and on every state document, is preserved by a refurbish that
 * doesn't change the format, and is replaced by one that does.
 */
@ReplayLogsOnFailure
@InjectFields
class FormatIdTest extends AbstractMongoDriverTest {
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
	void initialState_recordsFormatIdInManifestAndDocuments() {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		assertEquals(formatIdOf(bosk), formatIdInRootDocument(driverSettings.preferredDatabaseFormat()),
			"The root document should carry the manifest's format id");
	}

	@Test
	void sameFormatRefurbish_keepsFormatId() throws Exception {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		Identifier before = formatIdOf(bosk);
		bosk.getDriver(MongoDriver.class).refurbish();
		assertEquals(before, formatIdOf(bosk), "Refurbishing to the same format should keep the format id");
	}

	@Test
	void formatChangingRefurbish_mintsNewFormatId() throws Exception {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		Identifier before = formatIdOf(bosk);

		MongoDriverSettings refurbishedSettings = driverSettings.toBuilder()
			.preferredDatabaseFormat(differentFormat(driverSettings.preferredDatabaseFormat()))
			.build();
		Bosk<TestEntity> refurbisher = newBosk(refurbishedSettings, "refurbisher");
		refurbisher.getDriver(MongoDriver.class).refurbish();

		assertNotEquals(before, formatIdOf(refurbisher), "Changing the format should mint a new format id");
		assertEquals(formatIdOf(refurbisher), formatIdInRootDocument(refurbishedSettings.preferredDatabaseFormat()),
			"The documents should carry the new format id");
	}

	@Test
	void mismatchedFormatId_failsToLoad() throws Exception {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		Identifier original = formatIdOf(bosk);

		// Stop this bosk before corrupting the collection: otherwise its receiver
		// spends the rest of the test failing to load the corrupted contents.
		bosk.getDriver(MongoDriver.class).close();

		// Corrupt one document, as something outside Bosk might.
		collection().updateOne(
			new BsonDocument("_id", new BsonString(rootDocumentId(driverSettings.preferredDatabaseFormat()))),
			new BsonDocument("$set", new BsonDocument(DocumentFields.formatId.name(), new BsonString("not-the-format-id")))
		);

		// Load with a format driver directly, without starting another bosk.
		SessionCollection sessionCollection = SessionCollection.of(collection(), mongoService.client());
		FormatDriver<TestEntity> driver = newFormatDriver(bosk, sessionCollection, original);
		InvalidCollectionContentsException e;
		try (var _ = sessionCollection.newReadOnlySession()) {
			e = assertThrows(InvalidCollectionContentsException.class, driver::loadAllState);
		}
		String message = e.getMessage();
		assertTrue(message.contains("Format id mismatch on state document"), "Message should describe the mismatch: " + message);
		assertTrue(message.contains(original.toString()), "Message should name the expected format id: " + message);
		assertTrue(message.contains("not-the-format-id"), "Message should name the found format id: " + message);
	}

	@Test
	void updateThatUnsetsFormatId_isRejected() throws Exception {
		Bosk<TestEntity> bosk = newBosk(driverSettings, "bosk");
		Identifier formatId = formatIdOf(bosk);
		bosk.getDriver(MongoDriver.class).close();

		SessionCollection sessionCollection = SessionCollection.of(collection(), mongoService.client());
		AbstractFormatDriver<TestEntity> driver = newFormatDriver(bosk, sessionCollection, formatId);

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
			new UpdateDescription(List.of(DocumentFields.formatId.name()), new BsonDocument()),
			null,
			null,
			null,
			null,
			null
		);

		assertThrows(UnprocessableEventException.class, () -> driver.validateEventFormatId(event));
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
		assertEquals(manifest.formatId(), formatIdInRootDocument(driverSettings.preferredDatabaseFormat()),
			"The manifest and the state documents should carry the same format id");
	}

	private MongoDriverSettings differentSettings() {
		return driverSettings.toBuilder()
			.preferredDatabaseFormat(differentFormat(driverSettings.preferredDatabaseFormat()))
			.build();
	}

	private AbstractFormatDriver<TestEntity> newFormatDriver(Bosk<TestEntity> bosk, SessionCollection sessionCollection, Identifier formatId) {
		BsonSerializer serializer = new BsonSerializer();
		Duration flushTimeout = Duration.ofSeconds(1);
		return switch (driverSettings.preferredDatabaseFormat()) {
			case MongoDriverSettings.SequoiaFormat _ -> new SequoiaFormatDriver<>(
				bosk, sessionCollection, driverSettings, serializer, flushTimeout, bosk.driver(), formatId);
			case PandoFormat pandoFormat -> new PandoFormatDriver<>(
				bosk, sessionCollection, driverSettings, pandoFormat, serializer, flushTimeout, bosk.driver(), formatId);
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

	private Identifier formatIdOf(Bosk<TestEntity> bosk) {
		try {
			return bosk.getDriver(MainDriver.class).loadManifestInfo().manifest().formatId();
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private Identifier formatIdInRootDocument(MongoDriverSettings.DatabaseFormat format) {
		BsonDocument document = collection()
			.find(new BsonDocument("_id", new BsonString(rootDocumentId(format))))
			.first();
		return Identifier.from(document.getString(DocumentFields.formatId.name()).getValue());
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
