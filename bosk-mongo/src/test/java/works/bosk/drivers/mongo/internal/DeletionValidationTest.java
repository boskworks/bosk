package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Deletion is only legal for references that {@link Reference#isDeletable()}
 * accepts. The application ingress rejects anything else, and the Mongo driver
 * checks again at the write path, where a bad deletion would remove a field the
 * document needs.
 * <p>
 * The conformance suite can't cover this, because its canonical bosk sees each
 * update before the driver under test and rejects the update first. This test
 * reaches the driver through {@link Bosk#getDriver(Class)}, so the driver's own
 * guard is what rejects it.
 */
@ReplayLogsOnFailure
@InjectFields
class DeletionValidationTest extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				DatabaseFormat.SEQUOIA,
				PandoFormat.withGraftPoints("/catalog")
			),
			Stream.of(TestParameters.EventTiming.NORMAL));
	}

	@Test
	void nonDeletableTargets_areRejectedWithoutWriting(TestInfo testInfo) throws Exception {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialRoot,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		bosk.driver().flush();
		Refs refs = bosk.buildReferences(Refs.class);
		MongoDriver mongoDriver = bosk.getDriver(MongoDriver.class);

		Reference<TestEntity> root = bosk.rootReference();
		Reference<Identifier> rootIDRef = root.then(Identifier.class, TestEntity.Fields.id);
		List<Reference<?>> nonDeletable = List.of(
			root,
			root.then(String.class, TestEntity.Fields.string),
			refs.valuesString(),
			root.then(Object.class, TestEntity.Fields.variant, "string"),
			root.then(Object.class, TestEntity.Fields.variant, "string", "value")
		);

		Map<String, BsonDocument> before = snapshot();
		for (Reference<?> ref : nonDeletable) {
			@SuppressWarnings("unchecked")
			Reference<Object> target = (Reference<Object>) ref;
			assertThrows(IllegalArgumentException.class, () -> bosk.driver().submitDeletion(target));
			assertThrows(IllegalArgumentException.class,
				() -> bosk.driver().submitConditionalDeletion(target, rootIDRef, rootID));
			assertThrows(IllegalArgumentException.class, () -> mongoDriver.submitDeletion(target));
			assertThrows(IllegalArgumentException.class,
				() -> mongoDriver.submitConditionalDeletion(target, rootIDRef, rootID));
			assertThrows(IllegalArgumentException.class,
				() -> mongoDriver.submitConditionalDeletion(target, rootIDRef, Identifier.from("wrong")));
		}
		assertEquals(before, snapshot(), "Non-deletable deletions must not write to the collection");

		// The direct MongoDriver calls must not have disconnected the driver.
		Reference<String> rootString = root.then(String.class, TestEntity.Fields.string);
		bosk.driver().submitReplacement(rootString, "still connected");
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertEquals("still connected", rootString.value());
		}

		// The guard must not over-reject a legitimate deletion.
		Reference<TestEntity> entry = root.then(TestEntity.class, TestEntity.Fields.catalog, entity123.toString());
		bosk.driver().submitDeletion(entry);
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertNull(entry.valueIfExists(), "A deletable target must still be deletable");
		}
	}

	private Map<String, BsonDocument> snapshot() {
		Map<String, BsonDocument> result = new TreeMap<>();
		try (var cursor = collection().find().cursor()) {
			while (cursor.hasNext()) {
				BsonDocument document = cursor.next();
				result.put(document.getString("_id").getValue(), document);
			}
		}
		return result;
	}

	private MongoCollection<BsonDocument> collection() {
		return mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
	}
}
