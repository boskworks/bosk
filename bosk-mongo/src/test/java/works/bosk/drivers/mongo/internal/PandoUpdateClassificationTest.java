package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import java.time.Duration;
import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static works.bosk.drivers.mongo.internal.AbstractMongoDriverTest.Refs;
import static works.bosk.drivers.mongo.internal.AbstractMongoDriverTest.entity123;
import static works.bosk.drivers.mongo.internal.AbstractMongoDriverTest.initialRoot;
import static works.bosk.drivers.mongo.internal.AbstractMongoDriverTest.rootID;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Checks which Pando updates are planned as a single-document retryable write
 * and which still require a multi-document transaction.
 */
class PandoUpdateClassificationTest {
	private static MongoService mongoService;
	private MongoClient client;
	private MongoCollection<BsonDocument> raw;
	private Bosk<TestEntity> bosk;
	private PandoFormatDriver<TestEntity> driver;

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@AfterAll
	static void closeMongoConnection() {
		mongoService.close();
	}

	@BeforeEach
	void setupDriver() {
		client = mongoService.client();
		raw = client
			.getDatabase(getClass().getSimpleName())
			.getCollection("collection", BsonDocument.class);
		bosk = new Bosk<>(boskName(), TestEntity.class, AbstractMongoDriverTest::initialRoot, BoskConfig.simple());
		useFormat(PandoFormat.withGraftPoints("/catalog"));
	}

	private void useFormat(PandoFormat format) {
		driver = new PandoFormatDriver<>(
			bosk,
			SessionCollection.of(raw, client),
			MongoDriverSettings.builder().preferredDatabaseFormat(format).build(),
			format,
			new BsonSerializer(),
			Duration.ofSeconds(1),
			bosk.driver(),
			Manifest.newFormatId());
	}

	@Test
	void fieldInsideRootDocument_isAtomic() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		assertInstanceOf(AtomicUpdateOperation.class,
			driver.submitReplacement(refs.values(), TestValues.blank()));
		assertInstanceOf(AtomicUpdateOperation.class,
			driver.submitReplacement(refs.valuesString(), "newValue"));
	}

	@Test
	void fieldInsideGraftPointEntry_isAtomic() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<TestEntity> entry = refs.catalog().then(entity123);
		Reference<String> entryString = entry.then(String.class, "string");
		assertInstanceOf(AtomicUpdateOperation.class,
			driver.submitReplacement(entryString, "newValue"));
	}

	@Test
	void root_replacementWithSubParts_isTransactional() throws InvalidTypeException {
		TestEntity root = initialRoot(bosk);
		assertInstanceOf(TransactionalUpdateOperation.class,
			driver.submitReplacement(bosk.rootReference(), root));
	}

	@Test
	void root_replacementInOneBigDocument_isAtomic() throws InvalidTypeException {
		useFormat(PandoFormat.oneBigDocument());
		TestEntity root = initialRoot(bosk);
		assertInstanceOf(AtomicUpdateOperation.class,
			driver.submitReplacement(bosk.rootReference(), root));
	}

	@Test
	void wholeGraftPointEntry_replacement_isTransactional() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<TestEntity> entry = refs.catalog().then(entity123);
		TestEntity newValue = TestEntity.empty(entity123, refs.childCatalog(entity123));
		assertInstanceOf(TransactionalUpdateOperation.class,
			driver.submitReplacement(entry, newValue));
	}

	@Test
	void graftPointContainer_replacementWithEntries_isTransactional() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Catalog<TestEntity> newValue = Catalog.of(TestEntity.empty(entity123, refs.childCatalog(entity123)));
		assertInstanceOf(TransactionalUpdateOperation.class,
			driver.submitReplacement(refs.catalog(), newValue));
	}

	@Test
	void graftPointContainer_replacementWithoutEntries_isAtomic() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		assertInstanceOf(AtomicUpdateOperation.class,
			driver.submitReplacement(refs.catalog(), Catalog.empty()));
	}

	@Test
	void conditionalFieldCreation_isAtomic() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		assertInstanceOf(AtomicUpdateOperation.class,
			driver.submitConditionalCreation(refs.valuesString(), "newValue"));
	}

	@Test
	void conditionalFieldReplacement_withSameDocumentPrecondition_isAtomic() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<Identifier> precondition = bosk.rootReference().then(Identifier.class, "id");
		assertInstanceOf(AtomicUpdateOperation.class,
			driver.submitConditionalReplacement(refs.valuesString(), "newValue", precondition, rootID));
	}

	@Test
	void conditionalFieldReplacement_withCrossDocumentPrecondition_isTransactional() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<Identifier> precondition = refs.catalog().then(entity123).then(Identifier.class, "id");
		assertInstanceOf(TransactionalUpdateOperation.class,
			driver.submitConditionalReplacement(refs.valuesString(), "newValue", precondition, entity123));
	}

	@Test
	void conditionalFieldDeletion_withSameDocumentPrecondition_isAtomic() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<Identifier> precondition = bosk.rootReference().then(Identifier.class, "id");
		assertInstanceOf(AtomicUpdateOperation.class,
			driver.submitConditionalDeletion(refs.valuesString(), precondition, rootID));
	}

	@Test
	void conditionalFieldDeletion_withCrossDocumentPrecondition_isTransactional() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<Identifier> precondition = refs.catalog().then(entity123).then(Identifier.class, "id");
		assertInstanceOf(TransactionalUpdateOperation.class,
			driver.submitConditionalDeletion(refs.valuesString(), precondition, entity123));
	}

	@Test
	void conditionalWholeEntryCreation_isTransactional() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<TestEntity> entry = refs.catalog().then(entity123);
		assertInstanceOf(TransactionalUpdateOperation.class,
			driver.submitConditionalCreation(entry, TestEntity.empty(entity123, refs.childCatalog(entity123))));
	}

	@Test
	void conditionalWholeEntryReplacement_isTransactional() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<TestEntity> entry = refs.catalog().then(entity123);
		Reference<Identifier> precondition = bosk.rootReference().then(Identifier.class, "id");
		assertInstanceOf(TransactionalUpdateOperation.class,
			driver.submitConditionalReplacement(entry, TestEntity.empty(entity123, refs.childCatalog(entity123)), precondition, rootID));
	}

	@Test
	void fieldDeletion_isAtomic() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		assertInstanceOf(AtomicUpdateOperation.class,
			driver.submitDeletion(refs.valuesString()));
	}

	@Test
	void wholeGraftPointEntryDeletion_isAtomic() throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		assertInstanceOf(AtomicUpdateOperation.class,
			driver.submitDeletion(refs.catalog().then(entity123)));
	}
}
