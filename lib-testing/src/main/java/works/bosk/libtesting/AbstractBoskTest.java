package works.bosk.libtesting;

import java.lang.reflect.Type;
import java.util.Optional;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.DriverFactory;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.ListValue;
import works.bosk.Listing;
import works.bosk.MapValue;
import works.bosk.Phantom;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.TaggedUnion;
import works.bosk.TaggedUnionCase;
import works.bosk.annotations.Enclosing;
import works.bosk.annotations.Self;
import works.bosk.annotations.TaggedUnionCaseMap;
import works.bosk.exceptions.InvalidTypeException;

import static java.util.Arrays.asList;
import static works.bosk.testing.BoskTestUtils.boskName;

public abstract class AbstractBoskTest {
	public record TestRoot(
		Identifier id,
		Catalog<TestEntity> entities,
		StringListValueSubclass someStrings,
		MapValue<String> someMappedStrings
	) implements Entity {
		public TestRoot withId(Identifier id) {
			return new TestRoot(id, entities, someStrings, someMappedStrings);
		}

		public TestRoot withEntities(Catalog<TestEntity> entities) {
			return new TestRoot(id, entities, someStrings, someMappedStrings);
		}

		public TestRoot withSomeStrings(StringListValueSubclass someStrings) {
			return new TestRoot(id, entities, someStrings, someMappedStrings);
		}

		public TestRoot withSomeMappedStrings(MapValue<String> someMappedStrings) {
			return new TestRoot(id, entities, someStrings, someMappedStrings);
		}

		public static final class Fields {
			public static final String id = "id";
			public static final String entities = "entities";
			public static final String someStrings = "someStrings";
			public static final String someMappedStrings = "someMappedStrings";
		}
	}

	public static class StringListValueSubclass extends ListValue<String> {
		public StringListValueSubclass(String... entries) {
			super(entries);
		}
	}

	public record TestEntity(
		Identifier id,
		String string,
		TestEnum testEnum,
		Catalog<TestChild> children,
		Listing<TestChild> oddChildren,
		SideTable<TestChild, String> stringSideTable,
		Phantoms phantoms,
		Optionals optionals,
		ImplicitRefs implicitRefs,
		TaggedUnion<Variant> variant
	) implements Entity {
		public TestEntity withId(Identifier id) {
			return new TestEntity(id, string, testEnum, children, oddChildren, stringSideTable, phantoms, optionals, implicitRefs, variant);
		}

		public TestEntity withString(String string) {
			return new TestEntity(id, string, testEnum, children, oddChildren, stringSideTable, phantoms, optionals, implicitRefs, variant);
		}

		public TestEntity withTestEnum(TestEnum testEnum) {
			return new TestEntity(id, string, testEnum, children, oddChildren, stringSideTable, phantoms, optionals, implicitRefs, variant);
		}

		public TestEntity withChildren(Catalog<TestChild> children) {
			return new TestEntity(id, string, testEnum, children, oddChildren, stringSideTable, phantoms, optionals, implicitRefs, variant);
		}

		public TestEntity withOddChildren(Listing<TestChild> oddChildren) {
			return new TestEntity(id, string, testEnum, children, oddChildren, stringSideTable, phantoms, optionals, implicitRefs, variant);
		}

		public TestEntity withStringSideTable(SideTable<TestChild, String> stringSideTable) {
			return new TestEntity(id, string, testEnum, children, oddChildren, stringSideTable, phantoms, optionals, implicitRefs, variant);
		}

		public TestEntity withPhantoms(Phantoms phantoms) {
			return new TestEntity(id, string, testEnum, children, oddChildren, stringSideTable, phantoms, optionals, implicitRefs, variant);
		}

		public TestEntity withOptionals(Optionals optionals) {
			return new TestEntity(id, string, testEnum, children, oddChildren, stringSideTable, phantoms, optionals, implicitRefs, variant);
		}

		public TestEntity withImplicitRefs(ImplicitRefs implicitRefs) {
			return new TestEntity(id, string, testEnum, children, oddChildren, stringSideTable, phantoms, optionals, implicitRefs, variant);
		}

		public TestEntity withVariant(TaggedUnion<Variant> variant) {
			return new TestEntity(id, string, testEnum, children, oddChildren, stringSideTable, phantoms, optionals, implicitRefs, variant);
		}

		public TestEntity withChild(TestChild child) {
			return this.withChildren(children.with(child));
		}

		public static final class Fields {
			public static final String id = "id";
			public static final String string = "string";
			public static final String testEnum = "testEnum";
			public static final String children = "children";
			public static final String oddChildren = "oddChildren";
			public static final String stringSideTable = "stringSideTable";
			public static final String phantoms = "phantoms";
			public static final String optionals = "optionals";
			public static final String implicitRefs = "implicitRefs";
			public static final String variant = "variant";
		}
	}

	public record TestChild(
		Identifier id,
		String string,
		TestEnum testEnum,
		Catalog<TestChild> recursiveChildren
	) implements Entity {
		public TestChild withId(Identifier id) {
			return new TestChild(id, string, testEnum, recursiveChildren);
		}

		public TestChild withString(String string) {
			return new TestChild(id, string, testEnum, recursiveChildren);
		}

		public TestChild withTestEnum(TestEnum testEnum) {
			return new TestChild(id, string, testEnum, recursiveChildren);
		}

		public TestChild withRecursiveChildren(Catalog<TestChild> recursiveChildren) {
			return new TestChild(id, string, testEnum, recursiveChildren);
		}

		public static final class Fields {
			public static final String id = "id";
			public static final String string = "string";
			public static final String testEnum = "testEnum";
			public static final String recursiveChildren = "recursiveChildren";
		}
	}

	public enum TestEnum {
		OK,
		NOT_SO_OK
	}

	public record Optionals(
		Identifier id,
		Optional<String> optionalString,
		Optional<TestChild> optionalEntity,
		Optional<Reference<TestEntity>> optionalRef,
		Optional<Catalog<TestChild>> optionalCatalog,
		Optional<Listing<TestChild>> optionalListing,
		Optional<SideTable<TestChild, String>> optionalSideTable
	) implements Entity {
		public Optionals withId(Identifier id) {
			return new Optionals(id, optionalString, optionalEntity, optionalRef, optionalCatalog, optionalListing, optionalSideTable);
		}

		public Optionals withOptionalString(Optional<String> optionalString) {
			return new Optionals(id, optionalString, optionalEntity, optionalRef, optionalCatalog, optionalListing, optionalSideTable);
		}

		public Optionals withOptionalEntity(Optional<TestChild> optionalEntity) {
			return new Optionals(id, optionalString, optionalEntity, optionalRef, optionalCatalog, optionalListing, optionalSideTable);
		}

		public Optionals withOptionalRef(Optional<Reference<TestEntity>> optionalRef) {
			return new Optionals(id, optionalString, optionalEntity, optionalRef, optionalCatalog, optionalListing, optionalSideTable);
		}

		public Optionals withOptionalCatalog(Optional<Catalog<TestChild>> optionalCatalog) {
			return new Optionals(id, optionalString, optionalEntity, optionalRef, optionalCatalog, optionalListing, optionalSideTable);
		}

		public Optionals withOptionalListing(Optional<Listing<TestChild>> optionalListing) {
			return new Optionals(id, optionalString, optionalEntity, optionalRef, optionalCatalog, optionalListing, optionalSideTable);
		}

		public Optionals withOptionalSideTable(Optional<SideTable<TestChild, String>> optionalSideTable) {
			return new Optionals(id, optionalString, optionalEntity, optionalRef, optionalCatalog, optionalListing, optionalSideTable);
		}

		public static Optionals empty(Identifier id) {
			return new Optionals(
				id,
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty(),
				Optional.empty());
		}

		public static final class Fields {
			public static final String id = "id";
			public static final String optionalString = "optionalString";
			public static final String optionalEntity = "optionalEntity";
			public static final String optionalRef = "optionalRef";
			public static final String optionalCatalog = "optionalCatalog";
			public static final String optionalListing = "optionalListing";
			public static final String optionalSideTable = "optionalSideTable";
		}
	}

	public record Phantoms(
		Identifier id,
		Phantom<String> phantomString,
		Phantom<TestChild> phantomEntity,
		Phantom<Reference<TestEntity>> phantomRef,
		Phantom<Catalog<TestChild>> phantomCatalog,
		Phantom<Listing<TestChild>> phantomListing,
		Phantom<SideTable<TestChild, String>> phantomSideTable
	) implements Entity {
		public Phantoms withId(Identifier id) {
			return new Phantoms(id, phantomString, phantomEntity, phantomRef, phantomCatalog, phantomListing, phantomSideTable);
		}

		public Phantoms withPhantomString(Phantom<String> phantomString) {
			return new Phantoms(id, phantomString, phantomEntity, phantomRef, phantomCatalog, phantomListing, phantomSideTable);
		}

		public Phantoms withPhantomEntity(Phantom<TestChild> phantomEntity) {
			return new Phantoms(id, phantomString, phantomEntity, phantomRef, phantomCatalog, phantomListing, phantomSideTable);
		}

		public Phantoms withPhantomRef(Phantom<Reference<TestEntity>> phantomRef) {
			return new Phantoms(id, phantomString, phantomEntity, phantomRef, phantomCatalog, phantomListing, phantomSideTable);
		}

		public Phantoms withPhantomCatalog(Phantom<Catalog<TestChild>> phantomCatalog) {
			return new Phantoms(id, phantomString, phantomEntity, phantomRef, phantomCatalog, phantomListing, phantomSideTable);
		}

		public Phantoms withPhantomListing(Phantom<Listing<TestChild>> phantomListing) {
			return new Phantoms(id, phantomString, phantomEntity, phantomRef, phantomCatalog, phantomListing, phantomSideTable);
		}

		public Phantoms withPhantomSideTable(Phantom<SideTable<TestChild, String>> phantomSideTable) {
			return new Phantoms(id, phantomString, phantomEntity, phantomRef, phantomCatalog, phantomListing, phantomSideTable);
		}

		public static Phantoms empty(Identifier id) {
			return new Phantoms(
				id,
				Phantom.empty(),
				Phantom.empty(),
				Phantom.empty(),
				Phantom.empty(),
				Phantom.empty(),
				Phantom.empty());
		}

		public static final class Fields {
			public static final String id = "id";
			public static final String phantomString = "phantomString";
			public static final String phantomEntity = "phantomEntity";
			public static final String phantomRef = "phantomRef";
			public static final String phantomCatalog = "phantomCatalog";
			public static final String phantomListing = "phantomListing";
			public static final String phantomSideTable = "phantomSideTable";
		}
	}

	public record ImplicitRefs(
		Identifier id,
		Reference<ImplicitRefs> reference,
		Reference<TestEntity> enclosingRef,
		@Self Reference<ImplicitRefs> reference2,
		@Enclosing Reference<TestEntity> enclosingRef2
	) implements Entity {
		public ImplicitRefs withId(Identifier id) {
			return new ImplicitRefs(id, reference, enclosingRef, reference2, enclosingRef2);
		}

		public ImplicitRefs withReference(Reference<ImplicitRefs> reference) {
			return new ImplicitRefs(id, reference, enclosingRef, reference2, enclosingRef2);
		}

		public ImplicitRefs withEnclosingRef(Reference<TestEntity> enclosingRef) {
			return new ImplicitRefs(id, reference, enclosingRef, reference2, enclosingRef2);
		}

		public ImplicitRefs withReference2(Reference<ImplicitRefs> reference2) {
			return new ImplicitRefs(id, reference, enclosingRef, reference2, enclosingRef2);
		}

		public ImplicitRefs withEnclosingRef2(Reference<TestEntity> enclosingRef2) {
			return new ImplicitRefs(id, reference, enclosingRef, reference2, enclosingRef2);
		}

		public ImplicitRefs(Identifier id, @Self Reference<ImplicitRefs> reference, @Enclosing Reference<TestEntity> enclosingRef, Reference<ImplicitRefs> reference2, Reference<TestEntity> enclosingRef2) {
			this.id = id;
			this.reference = reference;
			this.enclosingRef = enclosingRef;
			this.reference2 = reference2;
			this.enclosingRef2 = enclosingRef2;
		}

		public static final class Fields {
			public static final String id = "id";
			public static final String reference = "reference";
			public static final String enclosingRef = "enclosingRef";
			public static final String reference2 = "reference2";
			public static final String enclosingRef2 = "enclosingRef2";
		}
	}

	public interface Variant extends TaggedUnionCase {
		@Override default String tag() {
			return "variant1";
		}

		@TaggedUnionCaseMap
		MapValue<Type> CASES = MapValue.singleton("variant1", TaggedUnionCase1.class);
	}

	public record TaggedUnionCase1(String stringField) implements Variant { }

	protected static Bosk<TestRoot> setUpBosk(DriverFactory<TestRoot> driverFactory) {
		return new Bosk<>(
			boskName(1),
			TestRoot.class,
			AbstractRoundTripTest::initialState,
			BoskConfig.<TestRoot>builder()
				.driverFactory(driverFactory).build()
		);
	}

	public static TestRoot initialState(Bosk<TestRoot> bosk) {
		return initialRoot(bosk);
	}

	public static TestRoot initialRoot(Bosk<TestRoot> bosk) {
		TestEntityBuilder teb;
		try {
			teb = new TestEntityBuilder(bosk);
		} catch (InvalidTypeException e) {
			throw new AssertionError(e);
		}
		Identifier parentID = Identifier.from("parent");
		Reference<TestEntity> parentRef = teb.entityRef(parentID);
		CatalogReference<TestChild> childrenRef = teb.childrenRef(parentID);
		Identifier child1ID = Identifier.from("child1");
		Identifier child2ID = Identifier.from("child2");
		Identifier child3ID = Identifier.from("child3");
		TestEntity entity = new TestEntity(parentID, "parent", TestEnum.OK, Catalog.of(
			new TestChild(child1ID, "child1", TestEnum.OK, Catalog.empty()),
			new TestChild(child2ID, "child2", TestEnum.NOT_SO_OK, Catalog.empty()),
			new TestChild(child3ID, "child3", TestEnum.OK, Catalog.empty())
		),
			Listing.empty(childrenRef).withID(child1ID).withID(child3ID),
			SideTable.empty(childrenRef, String.class).with(child2ID, "I'm child 2"),
			Phantoms.empty(Identifier.from("phantoms")),
			new Optionals(Identifier.from("optionals"),
				Optional.of("rootString"),
				Optional.of(new TestChild(Identifier.from("entity2"), "entity2", TestEnum.OK, Catalog.empty())),
				Optional.of(parentRef),
				Optional.of(Catalog.of(new TestChild(Identifier.from("OptionalTestEntity2"), "OptionalTestEntity2", TestEnum.OK, Catalog.empty()))),
				Optional.of(Listing.of(childrenRef, child2ID)),
				Optional.of(SideTable.empty(childrenRef, String.class).with(child2ID, "String value associated with " + child2ID))
			),
			new ImplicitRefs(Identifier.from("parent_implicitRefs"),
				teb.implicitRefsRef(parentID), parentRef,
				teb.implicitRefsRef(parentID), parentRef),
			TaggedUnion.of(new TaggedUnionCase1("taggedUnionCase1String")));
		return new TestRoot(
			Identifier.from("root"),
			Catalog.of(entity),
			new StringListValueSubclass("One", "Two"),
			MapValue.fromFunction(asList("key1", "key2"), k ->k + "_value"));
	}

}
