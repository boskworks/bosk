package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Reference;
import works.bosk.drivers.ForwardingDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Sequoia stores the whole state in one document. A REPLACE is applied even when
 * only metadata changed: the database is the source of truth.
 */
@ReplayLogsOnFailure
@InjectFields
class SequoiaDuplicateReplaceTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"SequoiaDuplicateReplaceTest",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(SEQUOIA)
				.timescaleMS(LONG_TIMESCALE)
				.database("SequoiaDuplicateReplaceTest")));
	}

	@Test
	void duplicateReplace_isApplied() throws Exception {
		Fixture fixture = newFixture();

		BsonDocument document = stateDocument();
		BsonDocument duplicate = document.clone();
		duplicate.put("diagnostics", new BsonDocument("attributes", new BsonDocument("test", new BsonString("changed"))));

		fixture.armed.set(true);
		collection().replaceOne(new BsonDocument("_id", document.get("_id")), duplicate);

		assertTrue(fixture.replaceSeen.await(20, SECONDS), "The change stream did not see the replace");
		assertEquals(1, fixture.rootReplacements.get(),
			"A REPLACE with unchanged state is still applied");
	}

	private record Fixture(
		Bosk<TestEntity> bosk,
		AtomicInteger rootReplacements,
		AtomicBoolean armed,
		CountDownLatch replaceSeen
	) {}

	private Fixture newFixture() throws Exception {
		AtomicInteger rootReplacements = new AtomicInteger();
		AtomicBoolean armed = new AtomicBoolean(false);
		CountDownLatch replaceSeen = new CountDownLatch(1);
		MainDriver.setProbes(TestProbes.noop().withListenerFactory(downstream ->
			new ForwardingChangeListener(downstream) {
				@Override
				public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, CollectionRewrittenException {
					super.onEvent(event);
					if (armed.get()
						&& event.getOperationType() == OperationType.REPLACE
						&& SequoiaFormatDriver.DOCUMENT_ID.equals(event.getDocumentKey().get("_id"))) {
						replaceSeen.countDown();
					}
				}
			}));
		try {
			DriverFactory<TestEntity> counting = (_, downstream) -> new ForwardingDriver(downstream) {
				@Override
				public <T> void submitReplacement(Reference<T> target, T newValue) {
					if (target.path().isEmpty()) {
						rootReplacements.incrementAndGet();
					}
					super.submitReplacement(target, newValue);
				}
			};
			DriverFactory<TestEntity> factory = DriverStack.of(driverFactory, counting);
			Bosk<TestEntity> bosk = new Bosk<>(
				boskName(),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory(factory).build());
			bosk.driver().flush();
			rootReplacements.set(0);
			return new Fixture(bosk, rootReplacements, armed, replaceSeen);
		} finally {
			MainDriver.resetProbes();
		}
	}

	private BsonDocument stateDocument() {
		BsonDocument document = collection()
			.find(new BsonDocument("_id", SequoiaFormatDriver.DOCUMENT_ID))
			.first();
		assertNotNull(document, "No Sequoia state document");
		return document;
	}

	private MongoCollection<BsonDocument> collection() {
		return mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
	}
}
