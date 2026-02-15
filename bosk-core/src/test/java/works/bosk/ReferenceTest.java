package works.bosk;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.BoskConfig.simpleDriver;

class ReferenceTest extends AbstractBoskTest {
	private Bosk<TestRoot> bosk;
	private TestRoot root;
	private Bosk<TestRoot>.ReadContext context;
	private Refs refs;

	@BeforeEach
	void setup() throws InvalidTypeException {
		this.bosk = setUpBosk(simpleDriver());
		context = bosk.readContext();
		this.root = bosk.rootReference().value();
		this.refs = bosk.buildReferences(Refs.class);
	}

	@AfterEach
	void closeReadContext() {
		context.close();
	}

	@Test
	void root_matches() {
		assertEquals(bosk.rootReference(), refs.entity(Identifier.from("parent")).root());
	}

	@Test
	void rootFields_referenceValue_returnsCorrectObject() {
		assertSame(root.entities(), refs.catalog().value());
		assertSame(root.someStrings(), refs.someStrings().value());
		assertSame(root.someMappedStrings(), refs.someMappedStrings().value());
	}

	@Test
	void parentFields_referenceValue_returnsCorrectObject() {
		Identifier parentID = Identifier.from("parent");
		Reference<TestEntity> parentRef = refs.entity(parentID);
		TestEntity parent = root.entities().get(parentID);
		assertSame(parent, parentRef.value());

		assertEquals(parent.string(), refs.string(parentID).value());
		assertSame(parent.testEnum(), refs.testEnum(parentID).value());
		assertSame(parent.children(), refs.children(parentID).value());
		assertSame(parent.oddChildren(), refs.oddChildren(parentID).value());
		assertSame(parent.stringSideTable(), refs.stringSideTable(parentID).value());
		assertSame(parent.phantoms(), refs.phantoms(parentID).value());
		assertSame(parent.optionals(), refs.optionals(parentID).value());
		assertSame(parent.implicitRefs(), refs.implicitRefs(parentID).value());
	}

	@Test
	void phantomFields_reference_nonexistent() {
		Identifier parentID = Identifier.from("parent");
		Reference<Phantoms> phantomsRef = refs.phantoms(parentID);
		Phantoms phantoms = root.entities().get(parentID).phantoms();
		assertSame(phantoms, phantomsRef.value());

		assertNull(refs.phantomString(parentID).valueIfExists());
		assertNull(refs.phantomEntity(parentID).valueIfExists());
		assertNull(refs.phantomRef(parentID).valueIfExists());
		assertNull(refs.phantomCatalog(parentID).valueIfExists());
		assertNull(refs.phantomListing(parentID).valueIfExists());
		assertNull(refs.phantomSideTable(parentID).valueIfExists());
	}

	@Test
	void optionalFields_referenceValueIfExists_returnsCorrectResult() {
		Identifier parentID = Identifier.from("parent");
		Reference<Optionals> optionalsRef = refs.optionals(parentID);
		Optionals optionals = root.entities().get(parentID).optionals();
		assertSame(optionals, optionalsRef.value());

		assertSame(optionals.optionalString().orElse(null), refs.optionalString(parentID).valueIfExists());
		assertSame(optionals.optionalEntity().orElse(null), refs.optionalEntity(parentID).valueIfExists());
		assertSame(optionals.optionalRef().orElse(null), refs.optionalRef(parentID).valueIfExists());
		assertSame(optionals.optionalCatalog().orElse(null), refs.optionalCatalog(parentID).valueIfExists());
		assertSame(optionals.optionalListing().orElse(null), refs.optionalListing(parentID).valueIfExists());
		assertSame(optionals.optionalSideTable().orElse(null), refs.optionalSideTable(parentID).valueIfExists());
	}

	@Test
	void forEach_definiteReference_noMatches() {
		assertForEachValueWorks(
			refs.entity(Identifier.from("nonexistent")),
			emptyList(),
			emptyList()
		);
	}

	@Test
	void forEach_definiteReference_oneMatch() {
		Identifier parentID = Identifier.from("parent");
		assertForEachValueWorks(
			refs.entity(parentID),
			singletonList(root.entities().get(parentID)),
			singletonList(BindingEnvironment.empty())
		);
	}

	@Test
	void forEach_indefiniteReference_noMatches() {
		assertForEachValueWorks(
			refs.anySideTableEntry(Identifier.from("nonexistent")),
			emptyList(),
			emptyList()
		);
	}

	@Test
	void forEach_indefiniteReference_oneMatch() {
		Identifier parentID = Identifier.from("parent");
		assertForEachValueWorks(
			refs.anySideTableEntry(parentID),
			singletonList(root.entities().get(parentID).stringSideTable().get(Identifier.from("child2"))),
			singletonList(BindingEnvironment.singleton("child", Identifier.from("child2")))
		);
	}

	@Test
	void forEach_indefiniteReference_multipleMatches() {
		Identifier parentID = Identifier.from("parent");
		Catalog<TestChild> children = root.entities().get(parentID).children();
		assertForEachValueWorks(
			refs.anyChild(parentID),
			children.stream().collect(toList()),
			children.idStream().map(id -> BindingEnvironment.singleton("child", id)).collect(toList())
		);
	}

	@Test
	void catalogRef_normalRef_equals() {
		assertEquals(refs.catalog(), refs.catalogNormalRef());
		assertEquals(refs.catalogNormalRef(), refs.catalog());
	}

	@Test
	void listingRef_normalRef_equals() {
		assertEquals(refs.listing(), refs.listingNormalRef());
		assertEquals(refs.listingNormalRef(), refs.listing());
	}

	@Test
	void sideTableRef_normalRef_equals() {
		assertEquals(refs.sideTable(), refs.sideTableNormalRef());
		assertEquals(refs.sideTableNormalRef(), refs.sideTable());
	}

	@Test
	void encloses() {
		Reference<Catalog<TestEntity>> entitiesRef = refs.catalog();
		Reference<TestEntity> parentRef = refs.entity(Identifier.from("parent"));
		Reference<String> stringRef = refs.string(Identifier.from("parent"));

		assertTrue(bosk.rootReference().encloses(bosk.rootReference()));
		assertTrue(bosk.rootReference().encloses(entitiesRef));
		assertTrue(bosk.rootReference().encloses(parentRef));
		assertTrue(bosk.rootReference().encloses(stringRef));

		assertTrue(entitiesRef.encloses(entitiesRef));
		assertTrue(entitiesRef.encloses(parentRef));
		assertTrue(entitiesRef.encloses(stringRef));

		assertFalse(entitiesRef.encloses(bosk.rootReference()));
		assertFalse(parentRef.encloses(entitiesRef));

		Reference<TestEntity> otherEntityRef = refs.entity(Identifier.from("other"));
		assertFalse(parentRef.encloses(otherEntityRef));
		assertFalse(otherEntityRef.encloses(parentRef));
	}

	@Test
	void truncatedTo() throws InvalidTypeException {
		Identifier parentID = Identifier.from("parent");
		Reference<String> stringRef = refs.string(parentID);

		assertEquals(bosk.rootReference(), stringRef.truncatedTo(TestRoot.class, 0));
		assertEquals(refs.catalog(), stringRef.truncatedTo(Catalog.class, 1));
		assertEquals(refs.entity(parentID), stringRef.truncatedTo(TestEntity.class, 2));
		assertEquals(stringRef, stringRef.truncatedTo(String.class, 3));
	}

	@Test
	void truncatedBeforeFirstParameter() {
		Identifier id = Identifier.from("id");

		assertEquals(refs.catalog(), refs.anyEntity().truncatedBeforeFirstParameter());
		assertEquals(refs.catalog(), refs.anySideTableEntry().truncatedBeforeFirstParameter());
		assertEquals(refs.stringSideTable(id), refs.anySideTableEntry(id).truncatedBeforeFirstParameter());
		assertThrows(IllegalArgumentException.class, () -> refs.anySideTableEntry(id, id).truncatedBeforeFirstParameter(),
			"Should throw when called on a reference with no parameters");
	}

	@Test
	void idAt() {
		Identifier parentID = Identifier.from("parent");
		Identifier childID = Identifier.from("child1");
		Reference<TestChild> childRef = refs.child(parentID, childID);

		assertEquals(parentID, childRef.idAt(1));
		assertEquals(childID, childRef.idAt(3));
	}

	@Test
	void valueOrDefault_valueOrElse() {
		Identifier parentID = Identifier.from("parent");
		Reference<TestEntity> nonexistentEntityRef = refs.entity(Identifier.from("nonexistent"));

		TestEntity defaultEntity = new TestEntity(
			Identifier.from("default"),
			"default",
			TestEnum.OK,
			Catalog.empty(),
			Listing.empty(refs.children(parentID)),
			SideTable.empty(refs.children(parentID), String.class),
			null, null, null, null
		);

		assertSame(defaultEntity, nonexistentEntityRef.valueOrDefault(defaultEntity));
		assertSame(defaultEntity, nonexistentEntityRef.valueOrElse(() -> defaultEntity));

		Reference<TestEntity> parentRef = refs.entity(parentID);
		TestEntity parent = parentRef.value();

		assertSame(parent, parentRef.valueOrDefault(defaultEntity));
		assertSame(parent, parentRef.valueOrElse(() -> defaultEntity));
	}

	@Test
	void optionalValue() {
		Reference<TestEntity> nonexistentEntityRef = refs.entity(Identifier.from("nonexistent"));

		assertEquals(Optional.empty(), nonexistentEntityRef.optionalValue());

		Reference<TestEntity> parentRef = refs.entity(Identifier.from("parent"));
		assertEquals(Optional.of(parentRef.value()), parentRef.optionalValue());
	}

	@Test
	void forEachValue_singleArgument() {
		Identifier parentID = Identifier.from("parent");
		Reference<TestChild> childrenRef = refs.anyChild(parentID);

		List<Identifier> expected = root.entities().get(parentID).children().idStream().collect(toList());
		List<Identifier> actual = new ArrayList<>();
		childrenRef.forEachValue(child -> actual.add(child.id()));
		assertEquals(expected, actual);
	}

	@Test
	void bindingMethods() {
		Identifier parentID = Identifier.from("parent");
		Reference<TestChild> indefiniteChildRef = refs.anyChild(parentID);

		Identifier child2 = Identifier.from("child2");
		Reference<TestChild> boundById = indefiniteChildRef.boundTo(child2);
		assertEquals(child2, boundById.idAt(3));
		assertEquals(child2, boundById.value().id());

		BindingEnvironment env = BindingEnvironment.singleton("child", child2);
		Reference<TestChild> boundByEnv = indefiniteChildRef.boundBy(env);
		assertEquals(boundById, boundByEnv);

		Path definitePath = Path.of(TestRoot.Fields.entities, "parent", TestEntity.Fields.children, "child2");
		assertEquals(env, indefiniteChildRef.parametersFrom(definitePath));

		Reference<TestChild> boundByPath = indefiniteChildRef.boundBy(definitePath);
		assertEquals(boundById, boundByPath);
	}

	@Test
	void miscellaneousMethods() {
		Identifier parentID = Identifier.from("parent");
		Reference<TestEntity> parentRef = refs.entity(parentID);
		Reference<String> stringRef = refs.string(parentID);

		assertTrue(parentRef.exists());
		assertTrue(stringRef.exists());

		Reference<TestEntity> nonexistent = refs.entity(Identifier.from("nonexistent"));
		assertFalse(nonexistent.exists());

		assertEquals("/entities/parent/string", stringRef.pathString());
		assertEquals("/", bosk.rootReference().pathString());

		assertTrue(bosk.rootReference().isRoot());
		assertFalse(parentRef.isRoot());

		assertEquals(parentRef, stringRef.enclosingReference(TestEntity.class));
		assertEquals(bosk.rootReference(), parentRef.enclosingReference(TestRoot.class));
	}

	private <T> void assertForEachValueWorks(Reference<T> ref, List<T> expectedValues, List<BindingEnvironment> expectedEnvironments) {
		List<T> actualValues = new ArrayList<>();
		List<BindingEnvironment> actualEnvironments = new ArrayList<>();
		ref.forEachValue((T v, BindingEnvironment e) -> {
			actualValues.add(v);
			actualEnvironments.add(e);
		}, BindingEnvironment.empty()); // TODO: test nontrivial initial environment

		assertEquals(expectedValues, actualValues);
		assertEquals(expectedEnvironments, actualEnvironments);
	}

	public interface Refs {
		@ReferencePath("/entities") CatalogReference<TestEntity> catalog();
		@ReferencePath("/entities") Reference<Catalog<TestEntity>> catalogNormalRef();
		@ReferencePath("/entities/-e-/oddChildren") ListingReference<TestChild> listing();
		@ReferencePath("/entities/-e-/oddChildren") Reference<Listing<TestChild>> listingNormalRef();
		@ReferencePath("/entities/-e-/stringSideTable") SideTableReference<TestChild, String> sideTable();
		@ReferencePath("/entities/-e-/stringSideTable") Reference<SideTable<TestChild, String>> sideTableNormalRef();

		@ReferencePath("/someStrings") Reference<StringListValueSubclass> someStrings();
		@ReferencePath("/someMappedStrings") Reference<MapValue<String>> someMappedStrings();

		@ReferencePath("/entities/-entity-") Reference<TestEntity> anyEntity();
		@ReferencePath("/entities/-entity-") Reference<TestEntity> entity(Identifier entity);
		@ReferencePath("/entities/-entity-/string") Reference<String> string(Identifier entity);
		@ReferencePath("/entities/-entity-/testEnum") Reference<TestEnum> testEnum(Identifier entity);
		@ReferencePath("/entities/-entity-/children") CatalogReference<TestChild> children(Identifier entity);
		@ReferencePath("/entities/-entity-/oddChildren") ListingReference<TestChild> oddChildren(Identifier entity);
		@ReferencePath("/entities/-entity-/stringSideTable") SideTableReference<TestChild, String> stringSideTable(Identifier entity);
		@ReferencePath("/entities/-entity-/phantoms") Reference<Phantoms> phantoms(Identifier entity);
		@ReferencePath("/entities/-entity-/optionals") Reference<Optionals> optionals(Identifier entity);
		@ReferencePath("/entities/-entity-/implicitRefs") Reference<ImplicitRefs> implicitRefs(Identifier entity);

		@ReferencePath("/entities/-entity-/phantoms/phantomString") Reference<String> phantomString(Identifier entity);
		@ReferencePath("/entities/-entity-/phantoms/phantomEntity") Reference<TestChild> phantomEntity(Identifier entity);
		@ReferencePath("/entities/-entity-/phantoms/phantomRef") Reference<Reference<TestEntity>> phantomRef(Identifier entity);
		@ReferencePath("/entities/-entity-/phantoms/phantomCatalog") CatalogReference<TestChild> phantomCatalog(Identifier entity);
		@ReferencePath("/entities/-entity-/phantoms/phantomListing") ListingReference<TestChild> phantomListing(Identifier entity);
		@ReferencePath("/entities/-entity-/phantoms/phantomSideTable") SideTableReference<TestChild, String> phantomSideTable(Identifier entity);

		@ReferencePath("/entities/-entity-/optionals/optionalString") Reference<String> optionalString(Identifier entity);
		@ReferencePath("/entities/-entity-/optionals/optionalEntity") Reference<TestChild> optionalEntity(Identifier entity);
		@ReferencePath("/entities/-entity-/optionals/optionalRef") Reference<Reference<TestEntity>> optionalRef(Identifier entity);
		@ReferencePath("/entities/-entity-/optionals/optionalCatalog") CatalogReference<TestChild> optionalCatalog(Identifier entity);
		@ReferencePath("/entities/-entity-/optionals/optionalListing") ListingReference<TestChild> optionalListing(Identifier entity);
		@ReferencePath("/entities/-entity-/optionals/optionalSideTable") SideTableReference<TestChild, String> optionalSideTable(Identifier entity);

		@ReferencePath("/entities/-entity-/stringSideTable/-child-") Reference<String> anySideTableEntry(Identifier... parameters);
		@ReferencePath("/entities/-entity-/children/-child-") Reference<TestChild> child(Identifier entity, Identifier child);
		@ReferencePath("/entities/-entity-/children/-child-") Reference<TestChild> anyChild(Identifier entity);
	}

}
