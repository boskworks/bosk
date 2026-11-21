package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;
import static works.bosk.drivers.mongo.internal.TestParameters.EventTiming.NORMAL;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * The orphan-document mode is part of the Pando format, so it is recorded in
 * the manifest alongside the graft points. HASTY is the only mode that can be
 * constructed, so these tests pin the round trip; whether the manifest beats
 * another bosk's preferred mode becomes testable when EARNEST is implemented.
 */
@ReplayLogsOnFailure
@InjectFields
class OrphanDocumentModeTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(PandoFormat.withGraftPoints("/catalog")),
			Stream.of(NORMAL)
		);
	}

	@Test
	void initializedCollection_recordsTheModeInTheManifest() {
		newBosk();

		PandoFormat preferred = (PandoFormat) driverSettings.preferredDatabaseFormat();
		BsonDocument manifest = collection()
			.find(new BsonDocument("_id", MANIFEST_ID))
			.first();
		assertNotNull(manifest, "The collection should have a manifest");
		assertEquals(preferred.orphanDocumentMode().name(),
			manifest.getDocument("pando").getString("orphanDocumentMode").getValue(),
			"The manifest should record the format's orphan-document mode");
	}

	@Test
	void loadedFormat_includesTheMode() throws UnrecognizedFormatException, FailedMongoClientSessionException, UninitializedCollectionException {
		Bosk<TestEntity> bosk = newBosk();

		Manifest manifest = bosk.getDriver(MainDriver.class).loadManifestInfo().manifest();
		assertEquals(driverSettings.preferredDatabaseFormat(), manifest.format(),
			"The loaded format should include the recorded orphan-document mode");
	}

	private Bosk<TestEntity> newBosk() {
		return new Bosk<>(
			boskName("Mode"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
	}

	private MongoCollection<BsonDocument> collection() {
		return mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
	}

}
