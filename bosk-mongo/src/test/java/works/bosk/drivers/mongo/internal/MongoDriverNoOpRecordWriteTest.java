package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.util.regex.Pattern;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonRegularExpression;
import org.bson.BsonString;
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
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A record-valued (document) write inside a nonexistent node is silently ignored,
 * like a scalar write -- but unlike a scalar write, it first scatters its
 * sub-parts into the database. If the main update then cannot be applied, those
 * sub-parts must be rolled back rather than left behind as orphan documents
 * (which would also leave change-stream events buffered in the demultiplexer
 * with no final event to pop them).
 */
@ReplayLogsOnFailure
public class MongoDriverNoOpRecordWriteTest extends AbstractMongoDriverTest {

	public MongoDriverNoOpRecordWriteTest() {
		super(MongoDriverSettings.builder()
			.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog/-x-/sideTable"))
			.timescaleMS(LONG_TIMESCALE)
			.database("MongoDriverNoOpRecordWriteTest"));
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
