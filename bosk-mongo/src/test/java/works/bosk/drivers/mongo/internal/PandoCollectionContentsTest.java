package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.time.Duration;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Identifier;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A Pando collection whose root document is gone is invalid contents, so loading
 * it should say that, and the receiver should report an invalid collection rather
 * than an internal error.
 */
@ReplayLogsOnFailure
@InjectFields
class PandoCollectionContentsTest extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"PandoCollectionContentsTest",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog"))
				.timescaleMS(LONG_TIMESCALE)
				.database("PandoCollectionContentsTest")));
	}

	@Test
	void partsWithoutRoot_isInvalidContents() throws UnrecognizedFormatException, UninitializedCollectionException, FailedMongoClientSessionException {
		Fixture fixture = initializedCollection();
		collection().deleteOne(new BsonDocument("_id", new BsonString("|")));

		try (var _ = fixture.sessionCollection().newReadOnlySession()) {
			InvalidCollectionContentsException e = assertThrows(InvalidCollectionContentsException.class,
				fixture.driver()::loadAllState);
			assertTrue(e.getMessage().contains("without a root document"), e.getMessage());
		}
	}

	@Test
	void noStateDocuments_isInvalidContents() throws UnrecognizedFormatException, UninitializedCollectionException, FailedMongoClientSessionException {
		Fixture fixture = initializedCollection();
		collection().deleteMany(new BsonDocument("_id", new BsonDocument("$regex", new BsonString("^[|]"))));

		try (var _ = fixture.sessionCollection().newReadOnlySession()) {
			InvalidCollectionContentsException e = assertThrows(InvalidCollectionContentsException.class,
				fixture.driver()::loadAllState);
			assertTrue(e.getMessage().contains("State document not found"), e.getMessage());
		}
	}

	private record Fixture(SessionCollection sessionCollection, AbstractFormatDriver<TestEntity> driver) {}

	/**
	 * Initializes the collection through a real Bosk, then returns a
	 * {@link FormatDriver} that reads it, so the tests can corrupt it first.
	 */
	private Fixture initializedCollection() throws UnrecognizedFormatException, UninitializedCollectionException, FailedMongoClientSessionException {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("bosk"),
			TestEntity.class,
			AbstractMongoDriverTest::initialRoot,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Identifier formatId = bosk.getDriver(MainDriver.class).loadManifestInfo().manifest().formatId();
		bosk.getDriver(MongoDriver.class).close();

		SessionCollection sessionCollection = SessionCollection.of(collection(), mongoService.client());
		AbstractFormatDriver<TestEntity> driver = new PandoFormatDriver<>(
			bosk,
			sessionCollection,
			driverSettings,
			(PandoFormat) driverSettings.preferredDatabaseFormat(),
			new BsonSerializer(),
			Duration.ofSeconds(1),
			bosk.driver(),
			formatId);
		return new Fixture(sessionCollection, driver);
	}

	private MongoCollection<BsonDocument> collection() {
		return mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
	}
}
