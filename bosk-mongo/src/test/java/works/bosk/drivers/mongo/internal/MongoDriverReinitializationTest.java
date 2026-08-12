package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.junit.Slow;

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.drivers.mongo.internal.MainDriver.COLLECTION_NAME;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests the behaviour of a bosk whose database collection is deleted and re-initialized
 * with different content by a different process while the bosk is still running.
 * The bosk must detect that the collection has been deleted and recreated,
 * even if the new collection's revision numbers happen to coincide with old ones.
 */
@Slow
@InjectFields
public class MongoDriverReinitializationTest extends AbstractMongoDriverTest {

	@BeforeEach
	void overrideLogging() {
		// This test deliberately provokes disconnections, so log errors only
		setLogging(ERROR, MainDriver.class, ChangeReceiver.class);
	}

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				MongoDriverSettings.DatabaseFormat.SEQUOIA,
				PandoFormat.oneBigDocument(),
				PandoFormat.withGraftPoints("/catalog", "/sideTable")
			),
			// LATE timing delays change-event delivery so that the flush (which is not delayed)
			// races ahead of the events that would otherwise carry the new state to the bosk.
			Stream.of(TestParameters.EventTiming.LATE)
		).map(b -> b.applyDriverSettings(s -> s
			.timescaleMS(LONG_TIMESCALE)
		));
	}

	@Test
	void collectionDeletedAndReinitialized_flushLoadsNewState() throws InterruptedException, IOException {
		LOGGER.debug("Initialize the database to a 'before' state");
		TestEntity beforeState = initializeDatabase("before reinitialization");

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(getClass().getSimpleName()),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		try (var _ = bosk.readSession()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		// An operator deletes the whole collection (documents and manifest) and someone
		// reinitializes it with different content before the bosk's flush happens.
		LOGGER.debug("Delete the collection and manifest, then reinitialize with different content");
		MongoCollection<BsonDocument> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME, BsonDocument.class);
		collection.drop();
		TestEntity afterState = initializeDatabase("after reinitialization");

		// A flushes must succeed and load the new state.
		LOGGER.debug("Flush the bosk and check that it loads the new state");
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertEquals(afterState, bosk.rootReference().value(),
				"flush must load the state of the reinitialized collection");
		}
	}

	private TestEntity initializeDatabase(String distinctiveString) {
		try {
			AtomicReference<MongoDriver> driverRef = new AtomicReference<>();
			Bosk<TestEntity> prepBosk = new Bosk<>(
				boskName("Prep " + getClass().getSimpleName()),
				TestEntity.class,
				bosk -> initialState(bosk).withString(distinctiveString),
				BoskConfig.<TestEntity>builder().driverFactory((b, d) -> {
					var mongoDriver = (MongoDriver) driverFactory.build(b, d);
					driverRef.set(mongoDriver);
					return mongoDriver;
				}).build());
			var driver = driverRef.get();
			driver.flush();
			driver.close();

			return initialRoot(prepBosk).withString(distinctiveString);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriverReinitializationTest.class);
}
