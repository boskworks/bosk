package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonRegularExpression;
import org.bson.BsonString;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.SideTableReference;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A write that cannot be applied (because its path passes through an absent
 * catalog entry) is silently ignored by the driver: it must not advance the
 * root document's revision, and must not leave orphaned sub-part documents
 * behind. The two variants differ only in the Pando graft-point configuration,
 * so each lives in its own {@link Nested} class.
 */
public class MongoDriverNoOpWriteTest {

	@Nested
	@ReplayLogsOnFailure
	@InjectFields
	class ScalarWrite extends AbstractMongoDriverTest {
		@InjectorMethod
		static Stream<ParameterSet> parameterSets() {
			return Stream.of(new ParameterSet(
				"MongoDriverNoOpWriteTest",
				MongoDriverSettings.builder()
					.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog"))
					.timescaleMS(LONG_TIMESCALE)
					.database("MongoDriverNoOpWriteTest")));
		}

		@Test
		void writeInsideNonexistentNode_doesNotBumpRevision() throws InvalidTypeException, IOException, InterruptedException {
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName(),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			bosk.driver().flush();

			// A reference to a field inside a catalog entry that doesn't exist.
			Reference<String> ghostField = bosk.buildReferences(Refs.class).catalog()
				.then(Identifier.from("ghost"))
				.then(String.class, "string");

			BsonInt64 revisionBefore = rootDocumentRevision();

			bosk.driver().submitReplacement(ghostField, "ignored");
			bosk.driver().flush();

			assertEquals(revisionBefore, rootDocumentRevision(),
				"A write that cannot be applied must not advance the revision");
		}

		private BsonInt64 rootDocumentRevision() {
			MongoCollection<BsonDocument> collection = mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(MainDriver.COLLECTION_NAME, BsonDocument.class);
			try (var cursor = collection.find(new BsonDocument("_id", new BsonString("|"))).cursor()) {
				return cursor.next().getInt64(BsonFormatter.DocumentFields.revision.name());
			}
		}
	}

	@Nested
	@ReplayLogsOnFailure
	@InjectFields
	class RecordWrite extends AbstractMongoDriverTest {
		@InjectorMethod
		static Stream<ParameterSet> parameterSets() {
			return Stream.of(new ParameterSet(
				"MongoDriverNoOpRecordWriteTest",
				MongoDriverSettings.builder()
					.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog/-x-/sideTable"))
					.timescaleMS(LONG_TIMESCALE)
					.database("MongoDriverNoOpRecordWriteTest")));
		}

		@Test
		void recordWriteInsideNonexistentNode_leavesNoOrphans() throws InvalidTypeException, IOException, InterruptedException {
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName(),
				TestEntity.class,
				this::singleEntryInitialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
			bosk.driver().flush();

			Refs refs = bosk.buildReferences(Refs.class);
			Identifier ghostID = Identifier.from("ghost");
			Reference<TestEntity> ghost = refs.catalog().then(ghostID);
			Reference<Catalog<TestEntity>> ghostCatalog = ghost.thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
			SideTableReference<TestEntity, TestEntity> ghostSideTable = ghost.thenSideTable(TestEntity.class, TestEntity.class, TestEntity.Fields.sideTable);

			// A side table containing one entry, written under the nonexistent entry.
			Identifier s1ID = Identifier.from("s1");
			SideTable<TestEntity, TestEntity> sideTable = SideTable.of(
				ghostCatalog,
				s1ID,
				TestEntity.empty(s1ID, ghostCatalog));

			BsonInt64 revisionBefore = rootDocumentRevision();

			bosk.driver().submitReplacement(ghostSideTable, sideTable);
			bosk.driver().flush();

			assertEquals(revisionBefore, rootDocumentRevision(),
				"A write that cannot be applied must not advance the revision");

			// No sub-part documents may be left behind under the nonexistent entry.
			assertTrue(countDocumentsWithIdPrefix("|catalog|ghost|") == 0,
				"No sub-part documents may be left behind under the nonexistent entry");
		}

		private TestEntity singleEntryInitialState(Bosk<TestEntity> bosk) throws InvalidTypeException {
			Refs refs = bosk.buildReferences(Refs.class);
			return initialRootWithEmptyCatalog(bosk)
				.withCatalog(Catalog.of(TestEntity.empty(entity123, refs.childCatalog(entity123))));
		}

		private BsonInt64 rootDocumentRevision() {
			MongoCollection<BsonDocument> collection = mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(MainDriver.COLLECTION_NAME, BsonDocument.class);
			try (var cursor = collection.find(new BsonDocument("_id", new BsonString("|"))).cursor()) {
				return cursor.next().getInt64(BsonFormatter.DocumentFields.revision.name());
			}
		}

		private long countDocumentsWithIdPrefix(String prefix) {
			MongoCollection<BsonDocument> collection = mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(MainDriver.COLLECTION_NAME, BsonDocument.class);
			return collection.countDocuments(new BsonDocument("_id",
				new BsonRegularExpression("^" + Pattern.quote(prefix))));
		}
	}

}
