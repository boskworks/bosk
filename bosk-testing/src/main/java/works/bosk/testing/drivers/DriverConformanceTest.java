package works.bosk.testing.drivers;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskConfig;
import works.bosk.BoskDiagnosticContext;
import works.bosk.BoskDriver;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.ListValue;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.ListingReference;
import works.bosk.MapValue;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.SideTableReference;
import works.bosk.TaggedUnion;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;
import works.bosk.junit.ParameterInjector;
import works.bosk.testing.drivers.state.Primitives;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestEntity.IdentifierCase;
import works.bosk.testing.drivers.state.TestEntity.StringCase;
import works.bosk.testing.drivers.state.TestEntity.Variant;
import works.bosk.testing.drivers.state.TestValues;
import works.bosk.testing.junit.Slow;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.util.Classes.listValue;
import static works.bosk.util.Classes.mapValue;
import static works.bosk.util.ReflectionHelpers.boxedClass;

/**
 * Tests the basic functionality of {@link BoskDriver}
 * across a variety of state tree situations by performing some series
 * of operations and then asserting that the resulting state matches
 * that computed by {@link BoskConfig#simpleDriver} performing the same operations.
 * <p>
 *
 * Use this by extending it and supplying a value for
 * the {@link #driverFactory} to test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@InjectFrom({
	DriverConformanceTest.EnclosingPathInjector.class,
	DriverConformanceTest.ChildIdInjector.class,
	DriverConformanceTest.TestEntityFieldInjector.class,
	DriverConformanceTest.PrimitiveRecordComponentInjector.class
})
public abstract class DriverConformanceTest extends AbstractDriverTest {
	// Subclass can initialize this as desired
	protected DriverFactory<TestEntity> driverFactory;

	public interface Refs {
		@ReferencePath("/id") Reference<Identifier> rootID();
		@ReferencePath("/catalog/-id-") Reference<TestEntity> catalogEntry(Identifier id);
		@ReferencePath("/nestedSideTable/outer") SideTableReference<TestEntity, TestEntity> outer();
		@ReferencePath("/nestedSideTable/outer/-inner-/catalog") CatalogReference<TestEntity> innerCatalog(Identifier id);
		@ReferencePath("/values/string") Reference<String> valuesString();
	}

	@Order(1) // If this doesn't work, nothing will
	@InjectedTest
	void initialState(@EnclosingCatalog Path enclosingCatalogPath) {
		initializeBoskWithCatalog(enclosingCatalogPath);
		assertCorrectBoskContents();
	}

	@Slow
	@InjectedTest
	void replaceIdentical(@EnclosingCatalog Path enclosingCatalogPath, @Child Identifier childID) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref.then(childID), newEntity(childID, ref));
		assertCorrectBoskContents();
	}

	@Slow
	@InjectedTest
	void replaceDifferent(@EnclosingCatalog Path enclosingCatalogPath, @Child Identifier childID) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref.then(childID), newEntity(childID, ref)
			.withString("replaced"));
		assertCorrectBoskContents();
	}

	@Slow
	@InjectedTest
	void replaceWholeThenParts(@EnclosingCatalog Path enclosingCatalogPath, @Child Identifier childID) throws InvalidTypeException {
		CatalogReference<TestEntity> catalogRef = initializeBoskWithCatalog(enclosingCatalogPath);
		Identifier awkwardID = Identifier.from(AWKWARD_ID);
		Reference<TestEntity> wholeEntityRef = catalogRef.then(awkwardID);
		CatalogReference<TestEntity> innerCatalogRef = wholeEntityRef.thenCatalog(TestEntity.class, "catalog");
		Reference<TestEntity> part1EntityRef = innerCatalogRef.then(childID);
		Reference<TestEntity> part2EntityRef = wholeEntityRef.thenSideTable(TestEntity.class, TestEntity.class, "sideTable").then(childID);
		Reference<ListingEntry> listingEntryRef = wholeEntityRef.thenListing(TestEntity.class, "listing").then(childID);

		driver.submitReplacement(wholeEntityRef,
			newEntity(awkwardID, catalogRef)
				.withCatalog(Catalog.of(
					emptyEntityAt(part1EntityRef)
						.withString("original-part1")
				))
				.withSideTable(SideTable.of(innerCatalogRef,
					child1ID,
					emptyEntityAt(part2EntityRef)
						.withString("original-part2")
				))
				.withListing(Listing.of(innerCatalogRef,
					child1ID
				)));
		driver.submitReplacement(part1EntityRef,
			emptyEntityAt(part1EntityRef)
				.withString("replaced-part1"));
		driver.submitReplacement(part2EntityRef,
			emptyEntityAt(part2EntityRef)
				.withString("replaced-part2"));
		driver.submitReplacement(listingEntryRef, LISTING_ENTRY);

		assertCorrectBoskContents();
	}

	@InjectedTest
	void replaceListingDomain(@EnclosingCatalog Path enclosingCatalogPath) throws InvalidTypeException, IOException, InterruptedException {
		CatalogReference<TestEntity> catalogRef = initializeBoskWithCatalog(enclosingCatalogPath);
		ListingReference<TestEntity> listingRef = catalogRef.thenListing(TestEntity.class, child1ID.toString(), "listing");
		Listing<TestEntity> initialListing = Listing.of(catalogRef, child1ID, child2ID);
		driver.submitReplacement(listingRef, initialListing);
		driver.flush();
		assertCorrectBoskContents(); // Correct starting state

		// Make a new listing with a different domain but the same contents
		CatalogReference<TestEntity> innerCatalogRef = catalogRef.thenCatalog(TestEntity.class, child1ID.toString(), "catalog");
		Listing<TestEntity> newListing = Listing.of(innerCatalogRef, initialListing.ids());
		driver.submitReplacement(listingRef, newListing);
		driver.flush();
		assertCorrectBoskContents();
	}

	@InjectedTest
	void replaceSideTableDomain(@EnclosingCatalog Path enclosingCatalogPath) throws InvalidTypeException, IOException, InterruptedException {
		CatalogReference<TestEntity> catalogRef = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.flush();
		Catalog<TestEntity> catalog;
		try (var _ = bosk.readSession()) {
			catalog = catalogRef.value();
		}
		SideTableReference<TestEntity, TestEntity> sideTableRef = catalogRef.thenSideTable(TestEntity.class, TestEntity.class, child1ID.toString(), "sideTable");
		SideTable<TestEntity, TestEntity> initialSideTable = SideTable.fromFunction(catalogRef, Stream.of(child1ID, child2ID), catalog::get);
		driver.submitReplacement(sideTableRef, initialSideTable);
		driver.flush();
		assertCorrectBoskContents(); // Correct starting state

		// Make a new side table with a different domain but the same contents
		CatalogReference<TestEntity> innerCatalogRef = catalogRef.thenCatalog(TestEntity.class, child1ID.toString(), "catalog");
		SideTable<TestEntity, TestEntity> newSideTable = SideTable.fromEntries(innerCatalogRef, initialSideTable.idEntrySet().stream());
		driver.submitReplacement(sideTableRef, newSideTable);
		driver.flush();
		assertCorrectBoskContents();
	}

	@InjectedTest
	void replaceNestedSideTableDomain() throws InvalidTypeException, IOException, InterruptedException {
		CatalogReference<TestEntity> catalogRef = initializeBoskWithCatalog(Path.just("catalog"));
		driver.flush();
		Refs refs = bosk.buildReferences(Refs.class);
		Catalog<TestEntity> catalog;
		try (var _ = bosk.readSession()) {
			catalog = catalogRef.value();
		}
		SideTable<TestEntity, TestEntity> initialSideTable = SideTable.fromFunction(catalogRef, Stream.of(child1ID, child2ID), catalog::get);
		driver.submitReplacement(refs.outer(), initialSideTable);
		driver.flush();
		assertCorrectBoskContents(); // Correct starting state

		// Make a new side table with a different domain but the same contents
		SideTable<TestEntity, TestEntity> newSideTable = SideTable.fromEntries(refs.innerCatalog(child1ID), initialSideTable.idEntrySet().stream());
		assert !initialSideTable.domain().equals(newSideTable.domain()): "Domains should be different or we're not actually testing a replace operation";
		driver.submitReplacement(refs.outer(), newSideTable);
		driver.flush();
		assertCorrectBoskContents();
	}

	@InjectedTest
	void deleteExisting(@EnclosingCatalog Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		// Here, we surgically initialize just the one child we want to delete, for a little variety.
		// Once upon a time, MongoDriver failed this specific case.
		Identifier childID = Identifier.unique("child");
		autoInitialize(ref.then(childID));
		driver.submitDeletion(ref.then(childID));
		assertCorrectBoskContents();
	}

	@InjectedTest
	void replaceCatalog(@EnclosingCatalog Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		Identifier unique = Identifier.unique("child");
		driver.submitReplacement(ref, Catalog.of(
			newEntity(child2ID, ref),
			newEntity(unique, ref),
			newEntity(child1ID, ref)
		));
		assertCorrectBoskContents();
	}

	@InjectedTest
	void replaceCatalogEmpty(@EnclosingCatalog Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref, Catalog.empty());
		assertCorrectBoskContents();
	}

	@InjectedTest
	void conditionalReplaceFirst(@EnclosingCatalog Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		Reference<Identifier> child1IDRef = ref.then(child1ID).then(Identifier.class, TestEntity.Fields.id);
		Reference<Identifier> child2IDRef = ref.then(child2ID).then(Identifier.class, TestEntity.Fields.id);

		LOGGER.debug("Self ID matches");
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 1"),
			child1IDRef, child1ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Self ID does not match");
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 2"),
			child1IDRef, child2ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Other ID matches");
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 1"),
			child2IDRef, child2ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Other ID does not match");
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 2"),
			child2IDRef, child1ID
		);
		assertCorrectBoskContents();

	}

	@InjectedTest
	void deleteForward(@EnclosingCatalog Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitDeletion(ref.then(child1ID));
		assertCorrectBoskContents();
		driver.submitDeletion(ref.then(child2ID));
		assertCorrectBoskContents();
	}

	@InjectedTest
	void deleteBackward(@EnclosingCatalog Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		assertCorrectBoskContents();
		LOGGER.debug("Delete second child");
		driver.submitDeletion(ref.then(child2ID));
		assertCorrectBoskContents();
		LOGGER.debug("Delete first child");
		driver.submitDeletion(ref.then(child1ID));
		assertCorrectBoskContents();
	}

	@InjectedTest
	void conditionalDelete(@EnclosingCatalog Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		Reference<Identifier> child1IDRef = ref.then(child1ID).then(Identifier.class, TestEntity.Fields.id);
		Reference<Identifier> child2IDRef = ref.then(child2ID).then(Identifier.class, TestEntity.Fields.id);

		LOGGER.debug("Self ID does not match - should have no effect");
		driver.submitConditionalDeletion(
			ref.then(child1ID),
			child1IDRef, child2ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Other ID does not match - should have no effect");
		driver.submitConditionalDeletion(
			ref.then(child1ID),
			child2IDRef, child1ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Other ID matches - child2 should disappear");
		driver.submitConditionalDeletion(
			ref.then(child2ID),
			child1IDRef, child1ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Self ID matches - child1 should disappear");
		driver.submitConditionalDeletion(
			ref.then(child1ID),
			child1IDRef, child1ID
		);
		assertCorrectBoskContents();

	}

	@InjectedTest
	void replaceFieldOfNonexistentEntry(@EnclosingCatalog Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(
			ref.then(String.class, "nonexistent", "string"),
			"new value");
		assertCorrectBoskContents();
		driver.submitReplacement(
			ref.then(String.class, "nonexistent", TestEntity.Fields.catalog, "nonexistent2", "string"),
			"new value");
		assertCorrectBoskContents();
	}

	@InjectedTest
	void deleteNonexistent(@EnclosingCatalog Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitDeletion(ref.then(Identifier.from("nonexistent")));
		assertCorrectBoskContents();
		driver.submitDeletion(ref.then(Identifier.from("nonexistent")).then(TestEntity.class,TestEntity.Fields.catalog, "nonexistent2"));
		assertCorrectBoskContents();
	}

	@InjectedTest
	void deleteCatalog_fails(@EnclosingCatalog Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		assertThrows(IllegalArgumentException.class, ()->
			driver.submitDeletion(ref));
		assertCorrectBoskContents();
	}

	@InjectedTest
	void deleteFields_fails(@EnclosingCatalog Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		// Use loops instead of parameters to avoid unnecessarily creating and initializing
		// a new bosk for every case. None of them affect the bosk anyway.
		for (Identifier childID: childIDs()) {
			for (String field: testEntityFields()) {
				Reference<Object> target = ref.then(Object.class, childID.toString(), field);
				assertThrows(IllegalArgumentException.class, () ->
					driver.submitDeletion(target), "Must not allow deletion of field " + target);
//				assertCorrectBoskContents(); // This slows down the test a lot, and don't realistically catch a lot more errors
			}
			assertCorrectBoskContents();
		}
	}

	@InjectedTest
	void optional() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		assertCorrectBoskContents();
		driver.submitReplacement(ref, TestValues.blank().withString("changed"));
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(ref, null));
		assertCorrectBoskContents();

		LOGGER.debug("Deleting {}", ref);
		driver.submitDeletion(ref);
		assertCorrectBoskContents();
		driver.submitDeletion(ref);
		assertCorrectBoskContents();
	}

	@InjectedTest
	void string() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<String> stringRef = ref.then(String.class, TestValues.Fields.string);
		LOGGER.debug("Submitting changed string");
		driver.submitReplacement(stringRef, "changed");
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(stringRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(stringRef));
		assertCorrectBoskContents();
	}

	@InjectedTest
	void enumeration() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		assertCorrectBoskContents();
		Reference<ChronoUnit> enumRef = ref.then(ChronoUnit.class, TestValues.Fields.chronoUnit);
		driver.submitReplacement(enumRef, MINUTES);
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(enumRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(enumRef));
		assertCorrectBoskContents();
	}

	@InjectedTest
	void variant() throws InvalidTypeException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		assertCorrectBoskContents();

		Reference<TaggedUnion<Variant>> variantRef = bosk.rootReference().thenTaggedUnion(Variant.class, TestEntity.Fields.variant);
		driver.submitReplacement(variantRef, TaggedUnion.of(new StringCase("value1")));
		assertCorrectBoskContents();
		driver.submitReplacement(variantRef, TaggedUnion.of(new IdentifierCase(Identifier.from("value2"))));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, () -> driver.submitDeletion(variantRef));
		assertCorrectBoskContents();

		Reference<StringCase> stringCaseRef = variantRef.then(StringCase.class, "string");
		assertThrows(IllegalArgumentException.class, () -> driver.submitReplacement(stringCaseRef, new StringCase("value2")));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, () -> driver.submitDeletion(stringCaseRef));
		assertCorrectBoskContents();

		Reference<IdentifierCase> idCaseRef = variantRef.then(IdentifierCase.class, "identifier");
		assertThrows(IllegalArgumentException.class, () -> driver.submitReplacement(idCaseRef, new IdentifierCase(Identifier.from("value3"))));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, () -> driver.submitDeletion(idCaseRef));
		assertCorrectBoskContents();
	}

	@InjectedTest
	void listValue_works() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<ListValue<String>> listRef = ref.then(listValue(String.class), TestValues.Fields.list);
		driver.submitReplacement(listRef, ListValue.of("this", "that"));
		assertCorrectBoskContents();
		driver.submitReplacement(listRef, ListValue.of("that", "this"));
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(listRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(listRef));
		assertCorrectBoskContents();
	}

	@InjectedTest
	void mapValue_works() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<MapValue<String>> mapRef = ref.then(mapValue(String.class), TestValues.Fields.map);

		// Check that key order is preserved
		driver.submitReplacement(mapRef, MapValue.fromFunction(asList("key1", "key2"), key->key+"_value"));
		assertCorrectBoskContents();
		driver.submitReplacement(mapRef, MapValue.fromFunction(asList("key2", "key1"), key->key+"_value"));
		assertCorrectBoskContents();

		// Check that blank keys and values are supported
		driver.submitReplacement(mapRef, MapValue.singleton("", ""));
		assertCorrectBoskContents();

		// Check that value-only replacement works, even if the key has periods in it.
		// (Not gonna lie... this is motivated by MongoDriver. But really all drivers should handle this case,
		// so it makes sense to put it here. We're trying to trick MongoDB into confusing a key with dots for
		// a series of nested fields.)
		MapValue<String> originalMapValue = MapValue.fromFunction(asList("key.with.dots.1", "key.with.dots.2"), k -> k + "_originalValue");
		driver.submitReplacement(mapRef, originalMapValue);
		assertCorrectBoskContents();
		MapValue<String> newMapValue = originalMapValue.with("key.with.dots.1", "_newValue");
		driver.submitReplacement(mapRef, newMapValue);
		assertCorrectBoskContents();

		// Check that the right submission-time exceptions are thrown
		assertThrows(NullPointerException.class, ()->driver.submitReplacement(mapRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(mapRef));
		assertCorrectBoskContents();
	}

	@InjectedTest
	@SuppressWarnings({"rawtypes","unchecked"})
	void primitive_works(@Primitive RecordComponent primitiveComponent) throws InvalidTypeException, InvocationTargetException, IllegalAccessException {
		setupBosksAndReferences(driverFactory);

		// Two values we can distinguish from each other
		var zero = Primitives.of(0);
		var one = Primitives.of(1);

		Reference ref = bosk.rootReference().then(boxedClass(primitiveComponent.getType()), "values", "primitives", primitiveComponent.getName());
		bosk.driver().submitReplacement(ref, primitiveComponent.getAccessor().invoke(zero));
		assertCorrectBoskContents();
		bosk.driver().submitReplacement(ref, primitiveComponent.getAccessor().invoke(one));
		assertCorrectBoskContents();
	}

	@SuppressWarnings("unused")
	static Stream<RecordComponent> primitiveComponent() {
		return Arrays.stream(Primitives.class.getRecordComponents());
	}

	@InjectedTest
	void flushNothing() throws IOException, InterruptedException {
		setupBosksAndReferences(driverFactory);
		// Flush before any writes should work
		driver.flush();
		assertCorrectBoskContents();
	}

	@InjectedTest
	void submitReplacement_propagatesDiagnosticContext() throws InvalidTypeException, IOException, InterruptedException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<String> ref = bosk.rootReference().then(String.class, "string");
		testDiagnosticContextPropagation(() -> bosk.driver().submitReplacement(ref, "value1"));
		// Do a second one with the same diagnostics to verify that they
		// still propagate even when they don't change
		testDiagnosticContextPropagation(() -> bosk.driver().submitReplacement(ref, "value2"));
	}

	@InjectedTest
	void submitConditionalReplacement_propagatesDiagnosticContext() throws InvalidTypeException, IOException, InterruptedException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<String> ref = bosk.rootReference().then(String.class, "string");
		testDiagnosticContextPropagation(() -> bosk.driver().submitConditionalReplacement(ref, "newValue", refs.rootID(), Identifier.from("root")));
	}

	@InjectedTest
	void submitConditionalCreation_propagatesDiagnosticContext() throws InvalidTypeException, IOException, InterruptedException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Refs refs = bosk.buildReferences(Refs.class);
		Identifier id = Identifier.from("testEntity");
		Reference<TestEntity> ref = refs.catalogEntry(id);
		testDiagnosticContextPropagation(() -> bosk.driver().submitConditionalCreation(ref, emptyEntityAt(ref)));
	}

	@InjectedTest
	void submitDeletion_propagatesDiagnosticContext() throws InvalidTypeException, IOException, InterruptedException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<TestEntity> ref = refs.catalogEntry(Identifier.unique("e"));
		autoInitialize(ref);
		testDiagnosticContextPropagation(() -> bosk.driver().submitDeletion(ref));
	}

	@InjectedTest
	void submitConditionalDeletion_propagatesDiagnosticContext() throws InvalidTypeException, IOException, InterruptedException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<TestEntity> ref = refs.catalogEntry(Identifier.unique("e"));
		autoInitialize(ref);
		testDiagnosticContextPropagation(() -> bosk.driver().submitConditionalDeletion(ref, refs.rootID(), Identifier.from("root")));
	}

	private void testDiagnosticContextPropagation(Runnable operation) throws IOException, InterruptedException {
		AtomicBoolean diagnosticsAreReady = new AtomicBoolean(false);
		Semaphore diagnosticsVerified = new Semaphore(0);
		bosk.hookRegistrar().registerHook("contextPropagatesToHook", bosk.rootReference(), _1 -> {
			// Note that this will run as soon as it's registered
			if (diagnosticsAreReady.get()) {
				BoskDiagnosticContext boskDiagnosticContext = bosk.diagnosticContext();
				LOGGER.debug("Received diagnostic attributes: {}", boskDiagnosticContext.getAttributes());
				assertEquals("attributeValue", boskDiagnosticContext.getAttribute("attributeName"));
				diagnosticsVerified.release();
			}
		});
		bosk.driver().flush();
		try (var _ = bosk.diagnosticContext().withAttribute("attributeName", "attributeValue")) {
			diagnosticsAreReady.set(true);
			LOGGER.debug("Running operation with diagnostic context");
			operation.run();
		}
		assertCorrectBoskContents();
		assertTrue(diagnosticsVerified.tryAcquire(5, SECONDS));
		diagnosticsAreReady.set(false); // Deactivate the hook
	}

	private Reference<TestValues> initializeBoskWithBlankValues(@EnclosingCatalog Path enclosingCatalogPath) throws InvalidTypeException {
		LOGGER.debug("initializeBoskWithBlankValues({})", enclosingCatalogPath);
		CatalogReference<TestEntity> catalogRef = initializeBoskWithCatalog(enclosingCatalogPath);
		Reference<TestValues> ref = catalogRef.then(child1ID).then(TestValues.class,
			TestEntity.Fields.values);
		driver.submitReplacement(ref, TestValues.blank());
		return ref;
	}

	/**
	 * @return a reference to {@code enclosingCatalogPath} which is a catalog containing
	 * two entities with ids {@link #child1ID} and {@link #child2ID}.
	 */
	private CatalogReference<TestEntity> initializeBoskWithCatalog(@EnclosingCatalog Path enclosingCatalogPath) {
		LOGGER.debug("initializeBoskWithCatalog({})", enclosingCatalogPath);
		setupBosksAndReferences(driverFactory);
		try {
			CatalogReference<TestEntity> ref = bosk.rootReference().thenCatalog(TestEntity.class, enclosingCatalogPath);

			TestEntity child1 = autoInitialize(ref.then(child1ID));
			TestEntity child2 = autoInitialize(ref.then(child2ID));

			Catalog<TestEntity> bothChildren = Catalog.of(child1, child2);
			driver.submitReplacement(ref, bothChildren);

			return ref;
		} catch (InvalidTypeException e) {
			throw new AssertionError(e);
		}
	}

	@Target(PARAMETER)
	@Retention(RUNTIME)
	@interface EnclosingCatalog {}

	@Target(PARAMETER)
	@Retention(RUNTIME)
	@interface Child {}

	@Target(PARAMETER)
	@Retention(RUNTIME)
	@interface TestEntityField {}

	record EnclosingPathInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(Path.class)
				&& parameter.isAnnotationPresent(EnclosingCatalog.class);
		}

		@Override
		public List<Path> values() {
			return List.of(
				Path.just(TestEntity.Fields.catalog),
				Path.of(TestEntity.Fields.catalog, AWKWARD_ID, TestEntity.Fields.catalog),
				Path.of(TestEntity.Fields.nestedSideTable, "outer", "inner", TestEntity.Fields.catalog),
				Path.of(TestEntity.Fields.sideTable, AWKWARD_ID, TestEntity.Fields.catalog, "parent", TestEntity.Fields.catalog),
				Path.of(TestEntity.Fields.sideTable, AWKWARD_ID, TestEntity.Fields.sideTable, "parent", TestEntity.Fields.catalog)
			);
		}
	}

	@SuppressWarnings("unused")
	static List<Identifier> childIDs() {
		return Stream.of(
			"child1",
//			"child2",
//			"nonexistent",
//			"id.with.dots",
//			"id/with/slashes",
//			"$id$with$dollars$",
//			"id:with:colons:",
//			"idWithEmojis\uD83C\uDF33\uD83E\uDDCA"
			AWKWARD_ID
		).map(Identifier::from).toList();
	}

	record ChildIdInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.isAnnotationPresent(Child.class)
				&& parameter.getType().equals(Identifier.class);
		}

		@Override
		public List<Identifier> values() {
			return childIDs();
		}
	}

	/**
	 * Contains all kinds of special characters
	 */
	public static final String AWKWARD_ID = "awkward$id.with%everything:/ +\uD83D\uDE09";

	static List<String> testEntityFields() {
		return List.of(
			TestEntity.Fields.id,
			TestEntity.Fields.string,
			TestEntity.Fields.catalog,
			TestEntity.Fields.listing,
			TestEntity.Fields.sideTable
		);
	}

	record TestEntityFieldInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.isAnnotationPresent(TestEntityField.class)
				&& parameter.getType().equals(String.class);
		}

		@Override
		public List<String> values() {
			return testEntityFields();
		}
	}

	@Target(PARAMETER)
	@Retention(RUNTIME)
	@interface Primitive {}

	record PrimitiveRecordComponentInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.isAnnotationPresent(Primitive.class)
				&& parameter.getType().equals(RecordComponent.class);
		}

		@Override
		public List<Object> values() {
			// IntelliJ may tell you this cast is redundant, but it's not in Java 25
			return List.of((Object[])Primitives.class.getRecordComponents());
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(DriverConformanceTest.class);
}
