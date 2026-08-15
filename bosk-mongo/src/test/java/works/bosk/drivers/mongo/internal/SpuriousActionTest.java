package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Stream;
import org.bson.BsonString;
import org.bson.Document;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.CatalogReference;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.ListingReference;
import works.bosk.Reference;
import works.bosk.drivers.BufferingDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static java.lang.Long.max;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.drivers.mongo.internal.TestParameters.SHORT_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;


/**
 * Tests that the driver takes no spurious actions: it emits no errors while
 * idle, flushes nothing before it is told to, and ignores changes to unrelated
 * databases, collections, and documents.
 */
@InjectFields
@ReplayLogsOnFailure
public class SpuriousActionTest extends AbstractMongoDriverTest {
	ErrorRecordingChangeListener.ErrorRecorder errorRecorder;

	@BeforeEach
	void setupErrorRecording() {
		errorRecorder = new ErrorRecordingChangeListener.ErrorRecorder();
		MainDriver.setProbes(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream))
			.withOnDisruption(TestProbes.failOnDisruption()));
	}

	@AfterEach
	void resetErrorRecording() {
		MainDriver.resetProbes();
	}

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.standardDriverSettings();
	}

	@Test
	void quiescent_noErrors() throws InterruptedException, IOException {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("quiescent"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		Thread.sleep(12*SHORT_TIMESCALE);

		errorRecorder.assertAllClear("after quiescent period");

		bosk.driver().flush();

		errorRecorder.assertAllClear("after flush");
	}

	@Test
	void noPrematureFlush() throws InvalidTypeException, InterruptedException, IOException {
		// Set up MongoDriver writing to a modified BufferingDriver that lets us
		// have tight control over all the comings and goings from MongoDriver.
		BlockingQueue<Reference<?>> replacementsSeen = new LinkedBlockingDeque<>();
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory((b, d) -> driverFactory.build(b, new BufferingDriver(d, b.context()) {
				@Override
				public <T> void submitReplacement(Reference<T> target, T newValue) {
					super.submitReplacement(target, newValue);
					replacementsSeen.add(target);
				}
			})).build());

		CatalogReference<TestEntity> catalogRef = bosk.rootReference().thenCatalog(TestEntity.class,
			TestEntity.Fields.catalog);
		ListingReference<TestEntity> listingRef = bosk.rootReference().thenListing(TestEntity.class,
			TestEntity.Fields.listing);

		// Make a change
		Reference<ListingEntry> ref = listingRef.then(entity123);
		bosk.driver().submitReplacement(ref, LISTING_ENTRY);

		// Give the driver a bit of time to make a mistake, if it's going to,
		// but not so long that we cause a timeout that wouldn't otherwise happen
		long budgetMillis = (1+driverSettings.timescaleMS()) / 2;
		while (budgetMillis > 0) {
			long startTime = currentTimeMillis();
			Reference<?> updatedRef = replacementsSeen.poll(budgetMillis, MILLISECONDS);
			if (ref.equals(updatedRef)) {
				// We've seen the expected update. This is pretty likely to be a good time
				// to proceed with the test.
				break;
			} else {
				long elapsedTime = currentTimeMillis() - startTime;
				budgetMillis -= max(elapsedTime, 1); // Always make progress despite the vagaries of the system clock
			}
		}

		try (var _ = bosk.readSession()) {
			TestEntity expected = initialRoot(bosk);
			TestEntity actual = bosk.rootReference().value();
			assertEquals(expected, actual, "MongoDriver should not have called downstream.flush() yet");
		}

		bosk.driver().flush();

		try (var _ = bosk.readSession()) {
			TestEntity expected = initialRoot(bosk).withListing(Listing.of(catalogRef, entity123));
			TestEntity actual = bosk.rootReference().value();
			assertEquals(expected, actual, "MongoDriver.flush() should reliably update the bosk");
		}

		errorRecorder.assertAllClear("after test");
	}

	@Test
	void unrelatedDatabase_ignored() throws InvalidTypeException, IOException, InterruptedException {
		tearDownActions.addFirst(mongoService.client().getDatabase("unrelated")::drop);
		doUnrelatedChangeTest("unrelated", MainDriver.COLLECTION_NAME, plausibleRootDocumentID().getValue());
	}

	@Test
	void unrelatedCollection_ignored() throws InvalidTypeException, IOException, InterruptedException {
		doUnrelatedChangeTest(driverSettings.database(), "unrelated", plausibleRootDocumentID().getValue());
	}

	@Test
	void unrelatedDoc_ignored() throws InvalidTypeException, IOException, InterruptedException {
		doUnrelatedChangeTest(driverSettings.database(), MainDriver.COLLECTION_NAME, "unrelated");
	}

	private void doUnrelatedChangeTest(String databaseName, String collectionName, String docID) throws IOException, InterruptedException, InvalidTypeException {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		MongoCollection<Document> counterfeitCollection = mongoService.client()
			.getDatabase(databaseName)
			.getCollection(collectionName);

		// Make a realistic-looking doc to try to fool the driver
		MongoCollection<Document> actualCollection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(MainDriver.COLLECTION_NAME);
		Document doc;
		try (MongoCursor<Document> cursor = actualCollection.find().limit(1).cursor()) {
			doc = cursor.next();
		}
		doc.put("_id", docID);
		doc.get("state", Document.class).put("string", "counterfeit");
		counterfeitCollection.insertOne(doc);

		bosk.driver().flush();
		TestEntity expected = initialRoot(bosk);
		try (var _ = bosk.readSession()) {
			TestEntity actual = bosk.rootReference().value();
			assertEquals(expected, actual);
		}

		errorRecorder.assertAllClear("after test");
	}

	@NonNull
	private BsonString plausibleRootDocumentID() {
		return (MongoDriverSettings.DatabaseFormat.SEQUOIA == driverSettings.preferredDatabaseFormat())
			? SequoiaFormatDriver.DOCUMENT_ID
			: new BsonString("|"); // Not every PANDO mode uses this, but hey, it's plausible
	}


	private static final Logger LOGGER = LoggerFactory.getLogger(SpuriousActionTest.class);
}
