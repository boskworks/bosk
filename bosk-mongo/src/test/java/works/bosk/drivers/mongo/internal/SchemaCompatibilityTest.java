package works.bosk.drivers.mongo.internal;

import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.With;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.StateTreeSerializer;
import works.bosk.TaggedUnion;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestValues;
import works.bosk.testing.junit.Slow;
import works.bosk.util.Classes;

import static ch.qos.logback.classic.Level.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;


/**
 * Tests the driver's tolerance of state written by a different version of the
 * state type: fields the current type doesn't know about are ignored on load,
 * update, and delete, and a missing required field falls back to the default
 * state.
 */
@InjectFields
@ReplayLogsOnFailure
public class SchemaCompatibilityTest extends AbstractMongoDriverTest {
	ErrorRecordingChangeListener.ErrorRecorder errorRecorder;

	@BeforeEach
	void setupErrorRecording() {
		errorRecorder = new ErrorRecordingChangeListener.ErrorRecorder();
		MainDriver.setProbes(TestProbes.noop()
			.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errorRecorder, downstream)));
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
	void initialStateHasNonexistentFields_ignored(TestInfo testInfo) throws InvalidTypeException {
		setLogging(ERROR, StateTreeSerializer.class);

		// Upon creating bosk, the initial value will be saved to MongoDB
		new Bosk<>(
			boskName("Newer"),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithValues,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		// Upon creating prevBosk, the state in the database will be loaded into the local.
		Bosk<OldEntity> prevBosk = new Bosk<>(
			boskName("Prev"),
			OldEntity.class,
			_ -> { throw new AssertionError("prevBosk should use the state from MongoDB"); },
			BoskConfig.<OldEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		OldEntity expected = OldEntity.withString(rootID.toString(), prevBosk);

		OldEntity actual;
		try (var _ = prevBosk.readSession()) {
			actual = prevBosk.rootReference().value();
		}
		assertEquals(expected, actual);

		errorRecorder.assertAllClear("after test");
	}

	@Test
	void updateHasNonexistentFields_ignored(TestInfo testInfo) throws InvalidTypeException, IOException, InterruptedException {
		setLogging(ERROR, StateTreeSerializer.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Newer"),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithEmptyCatalog,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Bosk<OldEntity> prevBosk = new Bosk<>(
			boskName("Prev"),
			OldEntity.class,
			_ -> { throw new AssertionError("prevBosk should use the state from MongoDB"); },
			BoskConfig.<OldEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		TestEntity initialRoot = initialRootWithEmptyCatalog(bosk);
		bosk.driver().submitReplacement(bosk.rootReference(),
			initialRoot
				.withString("replacementString")
				.withValues(Optional.of(TestValues.blank())));

		prevBosk.driver().flush();

		OldEntity oldEntity = OldEntity.withString("replacementString", prevBosk);

		OldEntity actual;
		try (var _ = prevBosk.readSession()) {
			actual = prevBosk.rootReference().value();
		}

		assertEquals(oldEntity, actual);

		errorRecorder.assertAllClear("after test");
	}

	@Test
	void updateNonexistentField_ignored(TestInfo testInfo) throws InvalidTypeException, IOException, InterruptedException {
		setLogging(ERROR, AbstractFormatDriver.class, StateTreeSerializer.class);

		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Newer"),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithEmptyCatalog,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Bosk<OldEntity> prevBosk = new Bosk<>(
			boskName("Prev"),
			OldEntity.class,
			_ -> {
				throw new AssertionError("prevBosk should use the state from MongoDB");
			},
			BoskConfig.<OldEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		Refs refs = bosk.buildReferences(Refs.class);
		bosk.driver().submitReplacement(refs.values(),
			TestValues.blank());

		prevBosk.driver().flush();

		OldEntity expected = OldEntity // unchanged from before
			.withString(rootID.toString(), prevBosk);

		OldEntity actual;
		try (var _ = prevBosk.readSession()) {
			actual = prevBosk.rootReference().value();
		}

		assertEquals(expected, actual);

		errorRecorder.assertAllClear("after test");
	}

	@Test
	void deleteNonexistentField_ignored(TestInfo testInfo) throws InvalidTypeException, IOException, InterruptedException {
		setLogging(ERROR, StateTreeSerializer.class);

		Bosk<TestEntity> newerBosk = new Bosk<>(
			boskName("Newer"),
			TestEntity.class,
			AbstractMongoDriverTest::initialStateWithEmptyCatalog,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		Bosk<OldEntity> prevBosk = new Bosk<>(
			boskName("Prev"),
			OldEntity.class,
			_ -> { throw new AssertionError("prevBosk should use the state from MongoDB"); },
			BoskConfig.<OldEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		Refs refs = newerBosk.buildReferences(Refs.class);
		newerBosk.driver().submitDeletion(refs.values());

		prevBosk.driver().flush();

		OldEntity oldEntity = OldEntity.withString(rootID.toString(), prevBosk); // unchanged

		OldEntity actual;
		try (var _ = prevBosk.readSession()) {
			actual = prevBosk.rootReference().value();
		}

		assertEquals(oldEntity, actual);

		errorRecorder.assertAllClear("after test");
	}

	@Test
	@Slow
	void databaseMissingField_fallsBackToDefaultState(TestInfo testInfo) throws InvalidTypeException, IOException, InterruptedException {
		setLogging(ERROR, ChangeReceiver.class);

		LOGGER.debug("Set up database with entity that has no string field");
		Bosk<OptionalEntity> setupBosk = new Bosk<>(
			boskName("Setup"),
			OptionalEntity.class,
			b -> OptionalEntity.withString(Optional.empty(), b),
			BoskConfig.<OptionalEntity>builder().driverFactory(createDriverFactory(logController, testInfo)).build());

		LOGGER.debug("Connect another bosk where the string field is mandatory");
		Bosk<TestEntity> testBosk = new Bosk<>(
			boskName("Test"),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		TestEntity expected1 = initialRoot(testBosk); // NOT what was put there by the setup bosk!
		TestEntity actual1;
		try (var _ = testBosk.readSession()) {
			actual1 = testBosk.rootReference().value();
		}

		assertEquals(expected1, actual1, "Disconnected bosk should use the default initial root");

		LOGGER.debug("Repair the bosk by writing the string value");
		setupBosk.driver().submitReplacement(
			setupBosk.rootReference().then(String.class, "string"),
			"stringValue");

		LOGGER.debug("Flush testBosk to get the state from the database");
		testBosk.driver().flush();

		Refs refs = testBosk.buildReferences(Refs.class);
		TestEntity expected2;
		try (var _ = setupBosk.readSession()) {
			// (Note that we don't bother flushing setupBosk because we don't need the latest value;
			// the variant field hasn't changed since it was initialized.)
			expected2 = TestEntity.empty(Identifier.from("optionalEntity"), refs.catalog())
				.withString("stringValue")
				.withVariant(setupBosk.rootReference().value().variant().get());
		}

		TestEntity actual2;
		try (var _ = testBosk.readSession()) {
			actual2 = testBosk.rootReference().value();
		}

		assertEquals(expected2, actual2, "Reconnected bosk should see the state from the database");

		assertEquals(0, errorRecorder.failureCount, "No connection failures");
		assertEquals(1, errorRecorder.disconnections.size(),
			"Expected 1 disconnection: DatabaseLoadException from DISCONNECT fallback");
	}

	@With
	public record OldEntity(
		Identifier id,
		String string,
		// We need catalog and sideTable because we use them in our PandoConfiguration
		Catalog<OldEntity> catalog,
		SideTable<OldEntity, OldEntity> sideTable
	) implements Entity {
		public static OldEntity withString(String value, Bosk<OldEntity> bosk) throws InvalidTypeException {
			Reference<Catalog<OldEntity>> catalogRef = bosk.rootReference().then(Classes.catalog(OldEntity.class), "catalog");
			return new OldEntity(
				rootID,
				value,
				Catalog.empty(),
				SideTable.empty(catalogRef)
			);
		}
	}

	@With
	public record OptionalEntity(
		Identifier id,
		Optional<String> string,
		Optional<Catalog<TestEntity>> catalog,
		Optional<Listing<TestEntity>> listing,
		Optional<SideTable<TestEntity, TestEntity>> sideTable,
		Optional<SideTable<TestEntity, SideTable<TestEntity, TestEntity>>> nestedSideTable,
		Optional<TaggedUnion<TestEntity.Variant>> variant,
		Optional<TestValues> values
	) implements Entity {
		static OptionalEntity withString(Optional<String> string, Bosk<OptionalEntity> bosk) throws InvalidTypeException {
			CatalogReference<TestEntity> domain = bosk.rootReference().thenCatalog(TestEntity.class, "catalog");
			return new OptionalEntity(
				Identifier.from("optionalEntity"),
				string,
				Optional.of(Catalog.empty()),
				Optional.of(Listing.empty(domain)),
				Optional.of(SideTable.empty(domain)),
				Optional.of(SideTable.empty(domain)),
				Optional.of(TaggedUnion.of(new TestEntity.StringCase("stringCase"))),
				Optional.empty());
		}
	}


	private static final Logger LOGGER = LoggerFactory.getLogger(SchemaCompatibilityTest.class);
}
