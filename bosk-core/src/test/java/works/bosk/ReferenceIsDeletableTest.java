package works.bosk;

import java.util.Map;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.TaggedUnionCaseMap;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.libtesting.AbstractBoskTest;
import works.bosk.testing.BoskTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Reference#isDeletable()} is a structural property of the path, matching
 * the {@code DeletableStep} rule in the path compiler.
 */
class ReferenceIsDeletableTest extends AbstractBoskTest {
	private final Bosk<TestRoot> bosk = new Bosk<>(
		BoskTestUtils.boskName(),
		TestRoot.class,
		AbstractBoskTest::initialRoot,
		BoskConfig.simple());

	private final Identifier parentID = Identifier.from("parent");
	private final Identifier child1ID = Identifier.from("child1");
	private final Identifier child2ID = Identifier.from("child2");

	@Test
	void rootIsNotDeletable() {
		assertFalse(bosk.rootReference().isDeletable());
	}

	@Test
	void catalogContainersAreNotDeletable() throws InvalidTypeException {
		assertFalse(ref("entities").isDeletable());
		assertFalse(ref("entities", parentID.toString(), "children").isDeletable());
	}

	@Test
	void catalogEntriesAreDeletable() throws InvalidTypeException {
		assertTrue(ref("entities", parentID.toString()).isDeletable());
		assertTrue(ref("entities", parentID.toString(), "children", child1ID.toString()).isDeletable());
	}

	@Test
	void listingEntriesAreDeletable() throws InvalidTypeException {
		assertFalse(ref("entities", parentID.toString(), "oddChildren").isDeletable());
		assertTrue(ref("entities", parentID.toString(), "oddChildren", child1ID.toString()).isDeletable());
	}

	@Test
	void sideTableEntriesAreDeletable() throws InvalidTypeException {
		assertFalse(ref("entities", parentID.toString(), "stringSideTable").isDeletable());
		assertTrue(ref("entities", parentID.toString(), "stringSideTable", child2ID.toString()).isDeletable());
	}

	@Test
	void optionalValuesAreDeletable() throws InvalidTypeException {
		assertTrue(ref("entities", parentID.toString(), "optionals", "optionalString").isDeletable());
		assertTrue(ref("entities", parentID.toString(), "optionals", "optionalEntity").isDeletable());
	}

	@Test
	void entriesInsideOptionalsAreDeletable() throws InvalidTypeException {
		assertTrue(ref("entities", parentID.toString(), "optionals", "optionalCatalog", "OptionalTestEntity2").isDeletable());
	}

	@Test
	void fieldsAreNotDeletable() throws InvalidTypeException {
		assertFalse(ref("entities", parentID.toString(), "string").isDeletable());
		assertFalse(ref("entities", parentID.toString(), "id").isDeletable());
		assertFalse(ref("entities", parentID.toString(), "testEnum").isDeletable());
		assertFalse(ref("entities", parentID.toString(), "optionals", "optionalEntity", "string").isDeletable());
	}

	@Test
	void phantomValuesAreDeletable() throws InvalidTypeException {
		assertTrue(ref("entities", parentID.toString(), "phantoms", "phantomString").isDeletable());
	}

	@Test
	void entriesInsidePhantomsAreStructurallyDeletable() throws InvalidTypeException {
		// A Phantom is always empty, but that is an existence fact, not a structural
		// one: the path ends at a Catalog entry and deleting it is a no-op.
		assertTrue(ref("entities", parentID.toString(), "phantoms", "phantomCatalog", child1ID.toString()).isDeletable());
	}

	@Test
	void taggedUnionCasesAndTheirContentsAreNotDeletable() throws InvalidTypeException {
		assertFalse(ref("entities", parentID.toString(), "variant").isDeletable());
		assertFalse(ref("entities", parentID.toString(), "variant", "variant1").isDeletable());
		assertFalse(ref("entities", parentID.toString(), "variant", "variant1", "stringField").isDeletable());
	}

	@Test
	void parameterizedReferencesReportTheirStructuralCapability() throws InvalidTypeException {
		Reference<Object> anyEntity = bosk.rootReference().then(Object.class, Path.of("entities", "-entity-"));
		assertTrue(anyEntity.isDeletable(), "An unbound catalog-entry reference is structurally deletable");
	}

	@Test
	void deletableStepUnderTaggedUnionCaseIsDeletable() {
		Bosk<UnionState> unionBosk = new Bosk<>(
			BoskTestUtils.boskName("union"),
			UnionState.class,
			_ -> new UnionState(TaggedUnion.of(new CatalogCase(Catalog.of(
				new TestChild(child1ID, "child1", TestEnum.OK, Catalog.empty()))))),
			BoskConfig.simple());

		try {
			Reference<Object> entry = unionBosk.rootReference().then(Object.class,
				Path.of("union", "withCatalog", "children", child1ID.toString()));
			assertTrue(entry.isDeletable(),
				"A catalog entry inside the active tagged-union case is deletable");
		} catch (InvalidTypeException e) {
			throw new AssertionError(e);
		}
	}

	private Reference<Object> ref(String... path) throws InvalidTypeException {
		return bosk.rootReference().then(Object.class, Path.of(path));
	}

	public record UnionState(TaggedUnion<UnionCase> union) implements StateTreeNode { }

	public interface UnionCase extends TaggedUnionCase {
		@TaggedUnionCaseMap
		MapValue<Class<? extends UnionCase>> CASES =
			MapValue.copyOf(Map.of("withCatalog", CatalogCase.class));

		@Override
		String tag();
	}

	public record CatalogCase(Catalog<TestChild> children) implements UnionCase {
		@Override
		public String tag() {
			return "withCatalog";
		}
	}
}
