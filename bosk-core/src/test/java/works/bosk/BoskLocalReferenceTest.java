package works.bosk;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import lombok.With;
import lombok.experimental.FieldNameConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NonexistentReferenceException;
import works.bosk.util.Classes;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static works.bosk.testing.BoskTestUtils.boskName;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.ReferenceUtils.rawClass;

/**
 * Tests that the Bosk can supply references that point to the right things, and
 * that its local driver performs updates and deletes that preserve the exact
 * objects supplied.
 *
 * <p>
 * Note that {@link BoskDriver} does not, in general, preserve the actual Java
 * objects supplied; it can replace them with equivalent objects, having all the
 * same fields with equivalent values. So the tests in here are not useful
 * tests of {@link BoskDriver} in general.
 *
 * @author Patrick Doyle
 *
 */
class BoskLocalReferenceTest {
	String boskName;
	Bosk<Root> bosk;
	Root root;
	Refs refs;

	public interface Refs {
		@ReferencePath("/entities") CatalogReference<TestEntity> entities();
		@ReferencePath("/entities/-entity-") Reference<TestEntity> entity(Identifier id);
	}

	@BeforeEach
	void initializeBosk() throws InvalidTypeException {
		boskName = boskName();
		Root initialRoot = new Root(1, Catalog.empty());
		bosk = new Bosk<>(boskName, Root.class, _ -> initialRoot, Bosk.simpleDriver(), Bosk.simpleRegistrar());
		refs = bosk.rootReference().buildReferences(Refs.class);
		Identifier ernieID = Identifier.from("ernie");
		Identifier bertID = Identifier.from("bert");
		TestEntity ernie = new TestEntity(ernieID, 1,
			refs.entity(bertID),
			Catalog.empty(),
			Listing.of(refs.entities(), bertID),
			SideTable.of(refs.entities(), bertID, "buddy"),
			ListValue.empty(),
			Optional.empty());
		TestEntity bert = new TestEntity(bertID, 1,
			refs.entity(ernieID),
			Catalog.empty(),
			Listing.of(refs.entities(), ernieID),
			SideTable.of(refs.entities(), ernieID, "pal"),
			ListValue.empty(),
			Optional.empty());
		bosk.driver().submitReplacement(refs.entities(), Catalog.of(ernie, bert));
		root = bosk.currentRoot();
	}

	@FieldNameConstants
	@With
	public record Root (
		Integer version,
		Catalog<TestEntity> entities
	) implements StateTreeNode { }

	@With
	@FieldNameConstants
	public record TestEntity(
		Identifier id,
		Integer version,
		Reference<TestEntity> refField,
		Catalog<TestEntity> catalog,
		Listing<TestEntity> listing,
		SideTable<TestEntity, String> sideTable,
		ListValue<String> listValue,
		Optional<TestEntity> optional
	) implements Entity { }

	@Test
	void testRootReference() throws Exception {
		checkReferenceProperties(bosk.rootReference(), Path.empty(), root);
		checkUpdates(bosk.rootReference(), ROOT_UPDATER);
		assertThrows(IllegalArgumentException.class, ()->bosk.driver().submitDeletion(bosk.rootReference()));
	}

	@Test
	void testCatalogReference() throws Exception {
		@SuppressWarnings({ "rawtypes", "unchecked" })
		Class<Catalog<TestEntity>> catalogClass = (Class<Catalog<TestEntity>>)(Class)Catalog.class;
		Path entitiesPath = Path.just(Root.Fields.entities);
		List<Reference<Catalog<TestEntity>>> testRefs = asList(
			bosk.rootReference().then(catalogClass, entitiesPath),
			bosk.rootReference().thenCatalog(TestEntity.class, entitiesPath),
			bosk.rootReference().thenCatalog(TestEntity.class, Root.Fields.entities),
			refs.entities());
		for (Reference<Catalog<TestEntity>> catalogRef: testRefs) {
			checkReferenceProperties(catalogRef, entitiesPath, root.entities());
			for (Identifier id: root.entities.ids()) {
				Reference<TestEntity> entityRef = catalogRef.then(TestEntity.class, id.toString());
				TestEntity expectedValue = root.entities().get(id);
				checkEntityReference(entityRef, Path.of(Root.Fields.entities, id.toString()), expectedValue);
				checkUpdates(entityRef, ENTITY_UPDATER);
				checkDeletion(entityRef, expectedValue);
			}
			checkEntityReference(catalogRef.then(TestEntity.class, "nonexistent"), Path.of(Root.Fields.entities, "nonexistent"), null);
		}
		// TODO: do the nested Catalog in TestEntity
	}

	@Test
	void testListingReference() throws Exception {
		for (Identifier id: root.entities.ids()) {
			Path listingPath = Path.of(Root.Fields.entities, id.toString(), TestEntity.Fields.listing);
			List<ListingReference<TestEntity>> testRefs = asList(
					bosk.rootReference().thenListing(TestEntity.class, listingPath),
					bosk.rootReference().thenListing(TestEntity.class, Root.Fields.entities, id.toString(), TestEntity.Fields.listing),
					bosk.rootReference().thenListing(TestEntity.class, Root.Fields.entities, "-entity-", TestEntity.Fields.listing).boundTo(id)
					);
			for (ListingReference<TestEntity> listingRef: testRefs) {
				// Check the Listing reference itself
				try {
					checkReferenceProperties(listingRef, listingPath, root.entities().get(id).listing());
					assertThrows(IllegalArgumentException.class, ()->bosk.driver().submitDeletion(listingRef));
				} catch (AssertionError e) {
					throw new AssertionError("Failed checks on listingRef " + listingRef + ": " + e.getMessage(), e);
				}

				// Check references to the Listing contents
				Listing<TestEntity> listing;
				Map<Identifier, TestEntity> entries;
				try (var _ = bosk.readContext()) {
					listing = listingRef.value();
					entries = listing.valueMap();
				}
				for (Identifier entryID: listing.ids()) {
					Reference<ListingEntry> entryRef = listingRef.then(entryID);
					Path entryPath = listingPath.then(entryID.toString());
					try {
						checkReferenceProperties(entryRef, entryPath, LISTING_ENTRY);
						checkDeletion(entryRef, LISTING_ENTRY);
						// Note: updates through Listing references don't affect
						// the referenced entity, so checkReferenceUpdates is
						// not appropriate here.
						// TODO: Add a test to verify this
					} catch (AssertionError e) {
						throw new AssertionError("Failed checks on entryRef " + entryRef + ": " + e.getMessage(), e);
					}
				}

				Identifier nonexistent = Identifier.from("nonexistent");
				Reference<ListingEntry> entryRef = listingRef.then(nonexistent);
				checkReferenceProperties(entryRef, listingPath.then("nonexistent"), null);
				checkDeletion(entryRef, null);
			}
		}
	}

	@Test
	void testSideTableReference() throws InvalidTypeException {
		for (Identifier id: root.entities.ids()) {
			Path sideTablePath = Path.of(Root.Fields.entities, id.toString(), TestEntity.Fields.sideTable);
			List<SideTableReference<TestEntity,String>> testRefs = asList(
					bosk.rootReference().thenSideTable(TestEntity.class, String.class, sideTablePath),
					bosk.rootReference().thenSideTable(TestEntity.class, String.class, Root.Fields.entities, id.toString(), TestEntity.Fields.sideTable),
					bosk.rootReference().thenSideTable(TestEntity.class, String.class, Root.Fields.entities, "-entity-", TestEntity.Fields.sideTable).boundTo(id)
					);
			for (SideTableReference<TestEntity,String> sideTableRef: testRefs) {
				SideTable<TestEntity, String> sideTable = root.entities().get(id).sideTable();
				try {
					checkReferenceProperties(sideTableRef, sideTablePath, sideTable);
				} catch (AssertionError e) {
					throw new AssertionError("Failed checkRefence on id " + id + ", sideTableRef " + sideTableRef);
				}
				try (var _ = bosk.readContext()) {
					for (Entry<Identifier, String> entry: sideTable.idEntrySet()) {
						Identifier key = entry.getKey();
						Reference<String> entryRef = sideTableRef.then(key);
						String expectedValue = entry.getValue();
						String actualValue = entryRef.value();
						assertEquals(expectedValue, actualValue, entryRef.toString());
					}
				}

				Identifier nonexistent = Identifier.from("nonexistent");
				Reference<String> entryRef = sideTableRef.then(nonexistent);
				checkReferenceProperties(entryRef, sideTablePath.then("nonexistent"), null);
				checkDeletion(entryRef, null);
			}
		}
	}

	@Test
	void testReferenceReference() throws Exception {
		for (Identifier id: root.entities.ids()) {
			Path refPath = Path.of(Root.Fields.entities, id.toString(), TestEntity.Fields.refField);
			List<Reference<Reference<TestEntity>>> testRefs = asList(
					bosk.rootReference().thenReference(TestEntity.class, refPath),
					bosk.rootReference().thenReference(TestEntity.class, Root.Fields.entities, id.toString(), TestEntity.Fields.refField),
					bosk.rootReference().thenReference(TestEntity.class, Root.Fields.entities, "-entity-", TestEntity.Fields.refField).boundTo(id)
			);
			for (Reference<Reference<TestEntity>> ref: testRefs) {
				Reference<TestEntity> refField = root.entities().get(id).refField();
				try {
					checkReferenceProperties(ref, refPath, refField);
					checkUpdates(ref, this::refUpdater);
					assertThrows(IllegalArgumentException.class, ()->bosk.driver().submitDeletion(ref));
				} catch (AssertionError e) {
					throw new AssertionError("Failed checkRefence on id " + id + ", referenceRef " + ref, e);
				}
			}
		}
	}

	/**
	 * A regression test for a type checking bug.
	 */
	@Test
	void testBogusReferenceReference() {
		assertThrows(InvalidTypeException.class, ()->
			bosk.rootReference().then(Classes.reference(String.class), Path.empty())); // Root object isn't a reference to a String
	}

	@Test
	void testName() {
		assertEquals(boskName, bosk.name());
	}

	private <T> void checkReferenceProperties(Reference<T> ref, Path expectedPath, T expectedValue) throws InvalidTypeException {
		if (expectedValue != null) {
			assertTrue(ref.targetClass().isAssignableFrom(expectedValue.getClass()));
			assertSame(ref.targetClass(), rawClass(ref.targetType()));
		}
		assertEquals(expectedPath, ref.path());
		assertEquals(expectedPath.urlEncoded(), ref.pathString());

		assertThrows(IllegalStateException.class, ref::value, "Can't read before ReadContext");
		try (var _ = bosk.readContext()) {
			T actualValue = ref.valueIfExists();
			assertSame(expectedValue, actualValue);

			if (expectedValue == null) {
				assertFalse(ref.exists());
				assertThrows(NonexistentReferenceException.class, ref::value);
				assertFalse(ref.optionalValue().isPresent());
			} else {
				assertTrue(ref.exists());
				assertSame(expectedValue, ref.value());
				assertSame(expectedValue, ref.optionalValue().get());
			}
		}
		assertThrows(IllegalStateException.class, ref::value, "Can't read after ReadContext");
	}

	private void checkEntityReference(Reference<TestEntity> ref, Path expectedPath, TestEntity expectedValue) throws InvalidTypeException {
		checkReferenceProperties(ref, expectedPath, expectedValue);

		assertEquals(Path.empty(), ref.enclosingReference(Root.class).path());

		// All kinds of "then" variants

		assertEquals(expectedPath.then(TestEntity.Fields.catalog), ref.thenCatalog(TestEntity.class, TestEntity.Fields.catalog).path());
		assertEquals(expectedPath.then(TestEntity.Fields.listing), ref.thenListing(TestEntity.class, TestEntity.Fields.listing).path());
		assertEquals(expectedPath.then(TestEntity.Fields.sideTable), ref.thenSideTable(TestEntity.class, String.class, TestEntity.Fields.sideTable).path());

		assertEquals(expectedPath.then(TestEntity.Fields.catalog), ref.then(Catalog.class, TestEntity.Fields.catalog).path());
		assertEquals(expectedPath.then(TestEntity.Fields.listing), ref.then(Listing.class, TestEntity.Fields.listing).path());
		assertEquals(expectedPath.then(TestEntity.Fields.sideTable), ref.then(SideTable.class, TestEntity.Fields.sideTable).path());

		try (var _ = bosk.readContext()) {
			if (expectedValue == null) {
				assertNull(ref.then(Catalog.class, TestEntity.Fields.catalog).valueIfExists());
				assertNull(ref.then(Listing.class, TestEntity.Fields.listing).valueIfExists());
				assertNull(ref.then(SideTable.class, TestEntity.Fields.sideTable).valueIfExists());
			} else {
				assertEquals(expectedValue.catalog(), ref.then(Catalog.class, TestEntity.Fields.catalog).value());
				assertEquals(expectedValue.listing(), ref.then(Listing.class, TestEntity.Fields.listing).value());
				assertEquals(expectedValue.sideTable(), ref.then(SideTable.class, TestEntity.Fields.sideTable).value());
			}
		}
	}

	private <T> void checkUpdates(Reference<T> ref, UnaryOperator<T> updater) throws InterruptedException, ExecutionException {
		Root originalRoot;
		T firstValue;
		assertThrows(IllegalStateException.class, ref::value, "Can't read from Bosk before ReadContext");
		try (var _ = bosk.readContext()) {
			originalRoot = bosk.rootReference().value();
			firstValue = ref.value();
		}
		assertThrows(IllegalStateException.class, ref::value, "Can't read from Bosk between ReadContexts");

		T secondValue = updater.apply(firstValue);
		T thirdValue = updater.apply(secondValue);
		try (var _ = bosk.readContext()) {
			assertSame(firstValue, ref.value(), "New ReadContext sees same value as before");
			bosk.driver().submitReplacement(ref, secondValue);
			assertSame(firstValue, ref.value(), "Bosk updates not visible during the same ReadContext");

			try (var _ = bosk.supersedingReadContext()) {
				assertSame(secondValue, ref.value(), "Superseding context sees the latest state");
				try (var _ = bosk.readContext()) {
					assertSame(secondValue, ref.value(), "Nested context matches outer context");
				}
			}

			try (var _ = bosk.readContext()) {
				assertSame(firstValue, ref.value(), "Nested context matches original outer context");
			}
		}

		try (
			var context = bosk.readContext()
		) {
			assertSame(secondValue, ref.value(), "New value is visible in next ReadContext");
			bosk.driver().submitReplacement(ref, thirdValue);
			assertSame(secondValue, ref.value(), "Bosk updates still not visible during the same ReadContext");
			ExecutorService executor = Executors.newFixedThreadPool(1);
			try {
				Future<?> future = executor.submit(() -> {
					IllegalStateException caught = null;
					try {
						ref.value();
					} catch (IllegalStateException e) {
						caught = e;
					} catch (Throwable e) {
						fail("Unexpected exception: ", e);
					}
					assertNotNull(caught, "New thread should not have any scope by default, so an exception should be thrown");
					try (var _ = bosk.readContext()) {
						assertSame(thirdValue, ref.value(), "Separate thread should see the latest state");
					}
					try (var inheritedContext = context.adopt()) {
						assertSame(secondValue, ref.value(), "Inherited scope should see the same state");

						try (var _ = inheritedContext.adopt()) {
							// Harmless to re-assert a scope you're already in
							assertSame(secondValue, ref.value(), "Inner scope should see the same state");
						}
					}
				});
				future.get();
			} finally {
				executor.shutdown();
			}
		}

		// Reset the bosk for subsequent tests.  This is necessary because we do
		// a lot of strong assertSame checks, and so it's not good enough to
		// leave it in an "equivalent" state; it must be composed of the same objects.
		bosk.driver().submitReplacement(bosk.rootReference(), originalRoot);
	}

	private <T> void checkDeletion(Reference<T> ref, T expectedValue) {
		Root originalRoot;
		try (var _ = bosk.readContext()) {
			originalRoot = bosk.rootReference().value();
			assertSame(expectedValue, ref.valueIfExists(), "Value is present before deletion");
			bosk.driver().submitDeletion(ref);
			assertSame(expectedValue, ref.valueIfExists(), "Bosk deletions not visible during the same ReadContext");
		}
		try (var _ = bosk.readContext()) {
			assertThrows(NonexistentReferenceException.class, ref::value);
			if (expectedValue != null) {
				bosk.driver().submitReplacement(ref, expectedValue);
				assertThrows(NonexistentReferenceException.class, ref::value);
			}
		}
		bosk.driver().submitReplacement(bosk.rootReference(), originalRoot);
	}

	private static final UnaryOperator<Root>       ROOT_UPDATER   = r -> r.withVersion(1 + r.version());
	private static final UnaryOperator<TestEntity> ENTITY_UPDATER = e -> e.withVersion(1 + e.version());

	private Reference<TestEntity> refUpdater(Reference<TestEntity> ref) {
		List<String> pathSegments = ref.path().segmentStream().collect(toList());
		pathSegments.set(1, "REPLACED_ID"); // second segment is the entity ID
		try {
			return bosk.rootReference().then(ref.targetClass(), Path.of(pathSegments));
		} catch (InvalidTypeException e) {
			throw new AssertionError("Unexpected!", e);
		}
	}
}
