package works.bosk.drivers.mongo.internal;

import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * The collection name is part of a bosk's replication group identity, along with the
 * database name: two bosks in the same database but different collections do not share
 * state. This is what allows a bosk to coexist in a database alongside the application's
 * own collections, or alongside an unrelated bosk.
 */
@ReplayLogsOnFailure
@InjectFields
public class MongoDriverCollectionTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"MongoDriverCollectionTest",
			MongoDriverSettings.builder()
				.timescaleMS(LONG_TIMESCALE)
				.database("MongoDriverCollectionTest")));
	}

	@Test
	void bosksInDifferentCollectionsDoNotShareState(TestInfo testInfo) throws InvalidTypeException, IOException, InterruptedException {
		String otherCollection = "otherCollection";
		// Start with a clean slate for the non-default collection too
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(otherCollection)
			.drop();
		DriverFactory<TestEntity> otherDriverFactory = (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<TestEntity>factory(
				mongoService.clientSettings(testInfo),
				driverSettings.toBuilder().collection(otherCollection).build(),
				new BsonSerializer()
			).build(boskInfo, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Bosk<TestEntity> otherBosk = new Bosk<>(
			boskName() + "-other",
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(otherDriverFactory).build());
		Refs refs = bosk.buildReferences(Refs.class);
		Refs otherRefs = otherBosk.buildReferences(Refs.class);

		bosk.driver().submitReplacement(refs.values(), TestValues.blank());
		bosk.driver().flush();
		assertEquals(initialState(bosk).withValues(Optional.of(TestValues.blank())), readRoot(bosk),
			"An update must be applied to the bosk that submitted it");
		assertEquals(initialState(otherBosk), readRoot(otherBosk),
			"An update in one collection must not appear in a different collection");

		otherBosk.driver().submitReplacement(otherRefs.values(), TestValues.blank());
		otherBosk.driver().flush();
		assertEquals(initialState(otherBosk).withValues(Optional.of(TestValues.blank())), readRoot(otherBosk),
			"An update must be applied to the bosk that submitted it");
		assertEquals(initialState(bosk).withValues(Optional.of(TestValues.blank())), readRoot(bosk),
			"An update in one collection must not appear in a different collection");
	}

	private TestEntity readRoot(Bosk<TestEntity> bosk) {
		try (var _ = bosk.readSession()) {
			return bosk.rootReference().value();
		}
	}
}
