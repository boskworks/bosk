package works.bosk;

import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.util.Classes;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReferenceTest extends AbstractBoskTest {
	private Bosk<TestRoot> bosk;
	private TestRoot root;
	private Bosk<TestRoot>.ReadContext context;
	private Refs refs;

	public interface Refs {
		@ReferencePath("/entities") CatalogReference<TestEntity> catalog();
		@ReferencePath("/entities") Reference<Catalog<TestEntity>> catalogNormalRef();
		@ReferencePath("/entities/-e-/oddChildren") ListingReference<TestEntity> listing();
		@ReferencePath("/entities/-e-/oddChildren") Reference<Listing<TestEntity>> listingNormalRef();
		@ReferencePath("/entities/-e-/stringSideTable") SideTableReference<TestEntity, String> sideTable();
		@ReferencePath("/entities/-e-/stringSideTable") Reference<SideTable<TestEntity, String>> sideTableNormalRef();
	}

	@BeforeEach
	void setup() throws InvalidTypeException {
		this.bosk = setUpBosk(Bosk::simpleDriver);
		context = bosk.readContext();
		this.root = bosk.rootReference().value();
		this.refs = bosk.buildReferences(Refs.class);
	}

	@AfterEach
	void closeReadContext() {
		context.close();
	}

	@Test
	void root_matches() throws InvalidTypeException {
		Reference<TestEntity> parentRef = bosk.rootReference().then(TestEntity.class, Path.of(
			TestRoot.Fields.entities, "parent"
		));
		assertEquals(bosk.rootReference(), parentRef.root());
	}

	@Test
	void rootFields_referenceValue_returnsCorrectObject() throws InvalidTypeException {
		assertSame(root.entities(), bosk.rootReference().thenCatalog(TestEntity.class, Path.just(
			TestRoot.Fields.entities
		)).value());
		assertSame(root.someStrings(), bosk.rootReference().then(StringListValueSubclass.class, Path.just(
			TestRoot.Fields.someStrings
		)).value());
		assertSame(root.someMappedStrings(), bosk.rootReference().then(Classes.mapValue(String.class), Path.just(
			TestRoot.Fields.someMappedStrings
		)).value());
	}

	@Test
	void parentFields_referenceValue_returnsCorrectObject() throws InvalidTypeException {
		Reference<TestEntity> parentRef = bosk.rootReference().then(TestEntity.class, Path.of(
			TestRoot.Fields.entities, "parent"
		));
		TestEntity parent = root.entities().get(Identifier.from("parent"));
		assertSame(parent, parentRef.value());

		assertEquals(parent.string(), parentRef.then(String.class, TestEntity.Fields.string).value());
		assertSame(parent.testEnum(), parentRef.then(TestEnum.class, TestEntity.Fields.testEnum).value());
		assertSame(parent.children(), parentRef.thenCatalog(TestChild.class, TestEntity.Fields.children).value());
		assertSame(parent.oddChildren(), parentRef.thenListing(TestChild.class, TestEntity.Fields.oddChildren).value());
		assertSame(parent.stringSideTable(), parentRef.thenSideTable(TestChild.class, String.class, TestEntity.Fields.stringSideTable).value());
		assertSame(parent.phantoms(), parentRef.then(Phantoms.class, TestEntity.Fields.phantoms).value());
		assertSame(parent.optionals(), parentRef.then(Optionals.class, TestEntity.Fields.optionals).value());
		assertSame(parent.implicitRefs(), parentRef.then(ImplicitRefs.class, TestEntity.Fields.implicitRefs).value());
	}

	@Test
	void phantomFields_reference_nonexistent() throws InvalidTypeException {
		Reference<Phantoms> phantomsRef = bosk.rootReference().then(Phantoms.class, Path.of(
			TestRoot.Fields.entities, "parent", TestEntity.Fields.phantoms
		));
		Phantoms phantoms = root.entities().get(Identifier.from("parent")).phantoms();
		assertSame(phantoms, phantomsRef.value());

		assertNull(phantomsRef.then(String.class, Phantoms.Fields.phantomString).valueIfExists());
		assertNull(phantomsRef.then(TestChild.class, Phantoms.Fields.phantomEntity).valueIfExists());
		assertNull(phantomsRef.then(Classes.reference(TestEntity.class), Phantoms.Fields.phantomRef).valueIfExists());
		assertNull(phantomsRef.then(Classes.catalog(TestChild.class), Phantoms.Fields.phantomCatalog).valueIfExists());
		assertNull(phantomsRef.then(Classes.listing(TestChild.class), Phantoms.Fields.phantomListing).valueIfExists());
		assertNull(phantomsRef.then(Classes.sideTable(TestChild.class, String.class), Phantoms.Fields.phantomSideTable).valueIfExists());
	}

	@Test
	void optionalFields_referenceValueIfExists_returnsCorrectResult() throws InvalidTypeException {
		Reference<Optionals> optionalsRef = bosk.rootReference().then(Optionals.class, Path.of(
			TestRoot.Fields.entities, "parent", TestEntity.Fields.optionals
		));
		Optionals optionals = root.entities().get(Identifier.from("parent")).optionals();
		assertSame(optionals, optionalsRef.value());

		assertSame(optionals.optionalString().orElse(null), optionalsRef.then(String.class, Optionals.Fields.optionalString).valueIfExists());
		assertSame(optionals.optionalEntity().orElse(null), optionalsRef.then(TestChild.class, Optionals.Fields.optionalEntity).valueIfExists());
		assertSame(optionals.optionalRef().orElse(null), optionalsRef.then(Classes.reference(TestEntity.class), Optionals.Fields.optionalRef).valueIfExists());
		assertSame(optionals.optionalCatalog().orElse(null), optionalsRef.then(Classes.catalog(TestChild.class), Optionals.Fields.optionalCatalog).valueIfExists());
		assertSame(optionals.optionalListing().orElse(null), optionalsRef.then(Classes.listing(TestChild.class), Optionals.Fields.optionalListing).valueIfExists());
		assertSame(optionals.optionalSideTable().orElse(null), optionalsRef.then(Classes.sideTable(TestChild.class, String.class), Optionals.Fields.optionalSideTable).valueIfExists());
	}

	@Test
	void forEach_definiteReference_noMatches() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.rootReference().then(TestEntity.class, Path.of(
				TestRoot.Fields.entities, "nonexistent")),
			emptyList(),
			emptyList()
		);
	}

	@Test
	void forEach_definiteReference_oneMatch() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.rootReference().then(TestEntity.class, Path.of(
				TestRoot.Fields.entities, "parent")),
			singletonList(root.entities().get(Identifier.from("parent"))),
			singletonList(BindingEnvironment.empty())
		);
	}

	@Test
	void forEach_indefiniteReference_noMatches() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.rootReference().then(String.class, Path.of(
				TestRoot.Fields.entities, "nonexistent", TestEntity.Fields.stringSideTable, "-child-")),
			emptyList(),
			emptyList()
		);
	}

	@Test
	void forEach_indefiniteReference_oneMatch() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.rootReference().then(String.class, Path.of(
				TestRoot.Fields.entities, "parent", TestEntity.Fields.stringSideTable, "-child-")),
			singletonList(root.entities().get(Identifier.from("parent")).stringSideTable().get(Identifier.from("child2"))),
			singletonList(BindingEnvironment.singleton("child", Identifier.from("child2")))
		);
	}

	@Test
	void forEach_indefiniteReference_multipleMatches() throws InvalidTypeException {
		Catalog<TestChild> children = root.entities().get(Identifier.from("parent")).children();
		assertForEachValueWorks(
			bosk.rootReference().then(TestChild.class, Path.of(
				TestRoot.Fields.entities, "parent", TestEntity.Fields.children, "-child-")),
			children.stream().collect(toList()),
			children.idStream().map(id -> BindingEnvironment.singleton("child", id)).collect(toList())
		);
	}

	@Test
	void catalogRef_normalRef_equals() throws InvalidTypeException {
		assertEquals(refs.catalog(), refs.catalogNormalRef());
		assertEquals(refs.catalogNormalRef(), refs.catalog());
	}

	@Test
	void listingRef_normalRef_equals() throws InvalidTypeException {
		assertEquals(refs.listing(), refs.listingNormalRef());
		assertEquals(refs.listingNormalRef(), refs.listing());
	}

	@Test
	void sideTableRef_normalRef_equals() throws InvalidTypeException {
		assertEquals(refs.sideTable(), refs.sideTableNormalRef());
		assertEquals(refs.sideTableNormalRef(), refs.sideTable());
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

}
