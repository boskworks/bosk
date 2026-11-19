package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoCollection;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.TaggedUnion;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.libtesting.BlockingGate;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.drivers.mongo.internal.AbstractMongoDriverTest.entity123;
import static works.bosk.drivers.mongo.internal.AbstractMongoDriverTest.entity124;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A conditional write whose precondition lives in a different document must take
 * MongoDB's write lock on that document, so that a concurrent write cannot
 * invalidate the precondition between the check and the commit.
 */
@ReplayLogsOnFailure
@InjectFields
class PandoPreconditionLockTest extends AbstractMongoDriverTest {
	private static final Identifier revision1 = Identifier.from("r1");
	private static final Identifier revision2 = Identifier.from("r2");

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return Stream.of(new ParameterSet(
			"PandoPreconditionLockTest",
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog"))
				.timescaleMS(LONG_TIMESCALE)
				.database("PandoPreconditionLockTest")));
	}

	@Test
	void externalWriteWaitsForPreconditionLock(TestInfo testInfo) throws Exception {
		AtomicBoolean armed = new AtomicBoolean(false);
		BlockingGate gate = new BlockingGate("conditional write's target write");
		Bosk<TestEntity> victim = newBosk(testInfo, "victim", TestProbes.noop().withWriteInterceptor(filter -> {
			BsonDocument filterDoc = filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
			// The target write is the one to entity 123's document; the lock write
			// goes to entity 124's.
			if (armed.get() && new BsonString("|catalog|123").equals(filterDoc.get("_id"))) {
				gate.signal();
				gate.awaitRelease(Duration.ofSeconds(10));
			}
		}));
		AtomicBoolean externalArmed = new AtomicBoolean(false);
		CountDownLatch externalAttempted = new CountDownLatch(1);
		Bosk<TestEntity> external = newBosk(testInfo, "external", TestProbes.noop().withWriteInterceptor(_ -> {
			if (externalArmed.get()) {
				externalAttempted.countDown();
			}
		}));

		Refs victimRefs = victim.buildReferences(Refs.class);
		Refs externalRefs = external.buildReferences(Refs.class);
		victim.driver().flush();
		external.driver().flush();

		Reference<String> conditionalTarget = victimRefs.catalog().then(entity123).then(String.class, "string");
		Reference<Identifier> preconditionRef = revisionRef(victimRefs, entity124);
		Reference<String> externalWarmupTarget = externalRefs.catalog().then(entity123).then(String.class, "string");
		Reference<Identifier> externalPreconditionRef = revisionRef(externalRefs, entity124);

		// Warm up the external writer so the timed write isn't its first one.
		external.driver().submitReplacement(externalWarmupTarget, "warmup");
		external.driver().flush();

		armed.set(true);
		externalArmed.set(true);
		AtomicReference<Throwable> conditionalFailure = new AtomicReference<>();
		CountDownLatch conditionalDone = new CountDownLatch(1);
		Thread conditionalWriter = new Thread(() -> {
			try {
				victim.driver().submitConditionalReplacement(
					conditionalTarget, "from conditional", preconditionRef, revision1);
			} catch (Throwable t) {
				conditionalFailure.set(t);
			} finally {
				conditionalDone.countDown();
			}
		}, "conditional-writer");
		AtomicReference<Throwable> externalFailure = new AtomicReference<>();
		CountDownLatch externalDone = new CountDownLatch(1);
		Thread externalWriter = new Thread(() -> {
			try {
				external.driver().submitReplacement(externalPreconditionRef, revision2);
			} catch (Throwable t) {
				externalFailure.set(t);
			} finally {
				externalDone.countDown();
			}
		}, "external-writer");

		boolean released = false;
		try {
			conditionalWriter.start();
			gate.awaitSignal(Duration.ofSeconds(20));
			externalWriter.start();
			// The interceptor runs before the write is sent, so this proves the write
			// is in flight while the conditional transaction holds the lock.
			assertTrue(externalAttempted.await(5, SECONDS), "The external write was never attempted");
			assertFalse(externalDone.await(1, SECONDS),
				"A write to the locked document must wait until the transaction ends");
			gate.release();
			released = true;
			assertTrue(conditionalDone.await(10, SECONDS), "Conditional write did not finish");
			assertTrue(externalDone.await(10, SECONDS), "External write did not finish");
		} finally {
			if (!released) {
				gate.release();
			}
			conditionalWriter.join(10_000);
			externalWriter.join(10_000);
		}

		if (conditionalFailure.get() != null) {
			throw new AssertionError("Conditional writer failed", conditionalFailure.get());
		}
		if (externalFailure.get() != null) {
			throw new AssertionError("External writer failed", externalFailure.get());
		}
		victim.driver().flush();
		external.driver().flush();
		try (var _ = victim.readSession()) {
			assertEquals("from conditional", conditionalTarget.value());
			assertEquals(revision2, preconditionRef.value());
		}
		try (var _ = external.readSession()) {
			assertEquals(revision2, externalPreconditionRef.value());
		}
	}

	@Test
	void conditionalWriteTakesLockWithoutBumpingRevision(TestInfo testInfo) throws Exception {
		Bosk<TestEntity> bosk = newBosk(testInfo, "bosk", TestProbes.noop());
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<Identifier> preconditionRef = revisionRef(refs, entity124);
		bosk.driver().flush();

		BsonDocument before = entityDocument(entity124);
		assertFalse(before.containsKey("lockId"), "Unexpected lock before the conditional write");
		BsonValue revisionBefore = before.get("revision");

		bosk.driver().submitConditionalReplacement(
			refs.catalog().then(entity123).then(String.class, "string"),
			"conditional",
			preconditionRef,
			revision1);
		bosk.driver().flush();

		BsonDocument after = entityDocument(entity124);
		assertTrue(after.containsKey("lockId"),
			"The precondition document should hold the transaction's write lock");
		assertEquals(revisionBefore, after.get("revision"),
			"Taking the lock must not bump the document's revision");
		try (var _ = bosk.readSession()) {
			assertEquals("conditional",
				refs.catalog().then(entity123).then(String.class, "string").value());
		}
	}

	@Test
	void failedPreconditionDoesNotLock(TestInfo testInfo) throws Exception {
		AtomicInteger writes = new AtomicInteger();
		Bosk<TestEntity> bosk = newBosk(testInfo, "bosk",
			TestProbes.noop().withWriteInterceptor(_ -> writes.incrementAndGet()));
		Refs refs = bosk.buildReferences(Refs.class);
		bosk.driver().flush();

		writes.set(0);
		bosk.driver().submitConditionalReplacement(
			refs.catalog().then(entity123).then(String.class, "string"),
			"must not apply",
			revisionRef(refs, entity124),
			Identifier.from("not-the-revision"));
		bosk.driver().flush();

		assertEquals(1, writes.get(),
			"The precondition check should be one conditional update");
		assertFalse(entityDocument(entity124).containsKey("lockId"),
			"A failed precondition must not leave a lock behind");
		try (var _ = bosk.readSession()) {
			assertEquals(entity123.toString(),
				refs.catalog().then(entity123).then(String.class, "string").value());
		}
	}

	@Test
	void abortedTargetRollsBackTheLock(TestInfo testInfo) throws Exception {
		AtomicInteger writes = new AtomicInteger();
		Bosk<TestEntity> bosk = newBosk(testInfo, "bosk",
			TestProbes.noop().withWriteInterceptor(_ -> writes.incrementAndGet()));
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<Identifier> preconditionRef = revisionRef(refs, entity124);
		bosk.driver().flush();

		writes.set(0);
		// The target is under a missing enclosing object, so the update matches
		// nothing and the transaction aborts after taking the lock.
		Reference<String> missingTarget = refs.childCatalog(entity123)
			.then(Identifier.from("999"))
			.then(String.class, "string");
		bosk.driver().submitConditionalReplacement(missingTarget, "must not apply", preconditionRef, revision1);
		bosk.driver().flush();

		assertEquals(2, writes.get(),
			"The lock and the target write are attempted; the target matches nothing");
		assertFalse(entityDocument(entity124).containsKey("lockId"),
			"An aborted update must roll back the lock");
	}

	/**
	 * The precondition is the value of an active tagged-union case, which can be
	 * replaced just like any other field, so it can change concurrently.
	 */
	private static Reference<Identifier> revisionRef(Refs refs, Identifier entityId) throws InvalidTypeException {
		Reference<TaggedUnion<TestEntity.Variant>> variantRef = refs.catalog()
			.then(entityId)
			.thenTaggedUnion(TestEntity.Variant.class, "variant");
		return variantRef
			.then(TestEntity.IdentifierCase.class, "identifier")
			.then(Identifier.class, "value");
	}

	private static TestEntity initialStateWithRevision(Bosk<TestEntity> bosk) throws InvalidTypeException {
		TestEntity root = AbstractMongoDriverTest.initialRoot(bosk);
		TestEntity withRevision = root.catalog().get(entity124)
			.withVariant(TaggedUnion.of(new TestEntity.IdentifierCase(revision1)));
		return root.withCatalog(root.catalog().with(withRevision));
	}

	private Bosk<TestEntity> newBosk(TestInfo testInfo, String name, TestProbes probes) {
		MainDriver.setProbes(probes);
		try {
			DriverFactory<TestEntity> factory = (boskInfo, downstream) -> {
				MongoDriver driver = MongoDriver.<TestEntity>factory(
					mongoService.clientSettings(testInfo),
					driverSettings,
					new BsonSerializer())
					.build(boskInfo, downstream);
				tearDownActions.addFirst(driver::close);
				return driver;
			};
			return new Bosk<>(
				boskName(name),
				TestEntity.class,
				PandoPreconditionLockTest::initialStateWithRevision,
				BoskConfig.<TestEntity>builder().driverFactory(factory).build());
		} finally {
			MainDriver.resetProbes();
		}
	}

	private BsonDocument entityDocument(Identifier id) {
		BsonDocument document = collection()
			.find(new BsonDocument("state.id", new BsonString(id.toString())))
			.first();
		assertNotNull(document, "No state document for entity " + id);
		return document;
	}

	private MongoCollection<BsonDocument> collection() {
		return mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
	}
}
