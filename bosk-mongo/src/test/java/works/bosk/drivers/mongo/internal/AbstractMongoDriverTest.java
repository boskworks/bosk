package works.bosk.drivers.mongo.internal;

import java.util.List;
import java.util.Optional;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.ListingReference;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.TaggedUnion;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;

abstract class AbstractMongoDriverTest extends MongoDriverFixture {
	protected static final Identifier entity123 = Identifier.from("123");
	protected static final Identifier entity124 = Identifier.from("124");
	protected static final Identifier rootID = Identifier.from("root");

	protected AbstractMongoDriverTest() {
	}

	@BeforeAll
	public static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@BeforeEach
	void setUp(TestInfo testInfo) {
		setupDriverFactory(testInfo);
		clearTearDown(testInfo);
	}

	@AfterEach
	void runTearDown(TestInfo testInfo) throws InterruptedException {
		tearDown(testInfo);
		assertNoOpenTransactions(testInfo);
	}

	/**
	 * Fails if a client of this test left a transaction open on the server.
	 */
	protected static void assertNoOpenTransactions(TestInfo testInfo) throws InterruptedException {
		mongoService.assertNoOpenTransactions(MongoService.clientApplicationName(testInfo));
	}

	public static TestEntity initialState(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		return initialRoot(testEntityBosk);
	}

	public static TestEntity initialRoot(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		Refs refs = testEntityBosk.buildReferences(Refs.class);
		return initialRootWithEmptyCatalog(testEntityBosk)
			.withCatalog(Catalog.of(
				TestEntity.empty(entity123, refs.childCatalog(entity123)),
				TestEntity.empty(entity124, refs.childCatalog(entity124))
			));
	}

	public static TestEntity initialStateWithValues(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		return initialRootWithValues(testEntityBosk);
	}

	public static TestEntity initialRootWithValues(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		return initialRootWithEmptyCatalog(testEntityBosk)
			.withValues(Optional.of(TestValues.blank()));
	}

	public static TestEntity initialStateWithEmptyCatalog(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		return initialRootWithEmptyCatalog(testEntityBosk);
	}

	public static TestEntity initialRootWithEmptyCatalog(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		Refs refs = testEntityBosk.buildReferences(Refs.class);
		return new TestEntity(rootID,
			rootID.toString(),
			Catalog.empty(),
			Listing.of(refs.catalog(), entity123),
			SideTable.empty(refs.catalog()),
			SideTable.empty(refs.catalog()),
			TaggedUnion.of(new TestEntity.StringCase(rootID.toString())),
			Optional.empty(),
			Optional.empty()
		);
	}

	/**
	 * A filter selecting the root state document.
	 */
	protected @NonNull BsonDocument rootDocumentsFilter() {
		return new BsonDocument("_id", new BsonDocument("$in",
			new BsonArray(List.of(new BsonString("|"), new BsonString("boskDocument")))));
	}

	public interface Refs {
		@ReferencePath("/catalog") CatalogReference<TestEntity> catalog();
		@ReferencePath("/catalog/-child-/catalog") CatalogReference<TestEntity> childCatalog(Identifier child);
		@ReferencePath("/listing") ListingReference<TestEntity> listing();
		@ReferencePath("/listing/-entity-") Reference<ListingEntry> listingEntry(Identifier entity);
		@ReferencePath("/values") Reference<TestValues> values();
		@ReferencePath("/values/string") Reference<String> valuesString();
	}
}
