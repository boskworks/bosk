package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Identifier;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static com.mongodb.client.model.Aggregates.match;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * The flush probes apply {@link AbstractFormatDriver#lastStateChangeFilter()} to the
 * change stream, not to the collection, so the filter has to name change-event
 * fields such as {@code documentKey._id}.
 */
@ReplayLogsOnFailure
@InjectFields
class SequoiaLastStateChangeFilterTest extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(MongoDriverSettings.DatabaseFormat.SEQUOIA),
			Stream.of(TestParameters.EventTiming.NORMAL));
	}

	@Test
	void lastStateChangeFilterMatchesRootDocumentWrite() throws Exception {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(getClass().getSimpleName()),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		bosk.driver().flush();

		Identifier epoch = bosk.getDriver(MainDriver.class).loadManifestInfo().manifest().epoch();
		MongoCollection<BsonDocument> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
		SessionCollection sessionCollection = SessionCollection.of(collection, mongoService.client());
		SequoiaFormatDriver<TestEntity> driver = new SequoiaFormatDriver<>(
			bosk, sessionCollection, driverSettings, new BsonSerializer(),
			Duration.ofSeconds(1), bosk.driver(), epoch);

		try (var cursor = collection.watch(List.of(match(driver.lastStateChangeFilter())))
			.maxAwaitTime(100, MILLISECONDS)
			.cursor()) {
			collection.updateOne(
				new BsonDocument("_id", SequoiaFormatDriver.DOCUMENT_ID),
				new BsonDocument("$set", new BsonDocument("state.string", new BsonString("filter test"))));

			ChangeStreamDocument<BsonDocument> event = null;
			long deadline = System.nanoTime() + SECONDS.toNanos(10);
			while (event == null && System.nanoTime() < deadline) {
				event = cursor.tryNext();
			}
			assertNotNull(event, "The probe filter must match a Sequoia state write");
		}
	}
}
