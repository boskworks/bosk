package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A write inside a nonexistent node is silently ignored: the local driver ignores
 * it (a replacement whose path passes through an absent catalog entry throws
 * {@code NonexistentEntryException}), so the Pando driver must ignore it too --
 * and must not bump the root document's revision as if a change had occurred.
 */
@ReplayLogsOnFailure
@InjectFields
public class MongoDriverNoOpWriteTest extends AbstractMongoDriverTest {

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
