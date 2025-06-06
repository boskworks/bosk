package works.bosk;

import java.lang.reflect.Type;
import java.util.Optional;
import lombok.With;
import lombok.experimental.FieldNameConstants;
import works.bosk.annotations.Enclosing;
import works.bosk.annotations.Self;
import works.bosk.annotations.VariantCaseMap;
import works.bosk.exceptions.InvalidTypeException;

import static java.util.Arrays.asList;
import static works.bosk.BoskTestUtils.boskName;

public abstract class AbstractBoskTest {
	@With
	@FieldNameConstants
	public record TestRoot(
		Identifier id,
		Catalog<TestEntity> entities,
		StringListValueSubclass someStrings,
		MapValue<String> someMappedStrings
	) implements Entity { }

	public static class StringListValueSubclass extends ListValue<String> {
		public StringListValueSubclass(String... entries) {
			super(entries);
		}
	}

	@With
	@FieldNameConstants
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
		public TestEntity withChild(TestChild child) {
			return this.withChildren(children.with(child));
		}
	}

	@With
	@FieldNameConstants
	public record TestChild(
		Identifier id,
		String string,
		TestEnum testEnum,
		Catalog<TestChild> recursiveChildren
	) implements Entity { }

	public enum TestEnum {
		OK,
		NOT_SO_OK
	}

	@With
	@FieldNameConstants
	public record Optionals(
		Identifier id,
		Optional<String> optionalString,
		Optional<TestChild> optionalEntity,
		Optional<Reference<TestEntity>> optionalRef,
		Optional<Catalog<TestChild>> optionalCatalog,
		Optional<Listing<TestChild>> optionalListing,
		Optional<SideTable<TestChild, String>> optionalSideTable
	) implements Entity {
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
	}

	@With
	@FieldNameConstants
	public record Phantoms(
		Identifier id,
		Phantom<String> phantomString,
		Phantom<TestChild> phantomEntity,
		Phantom<Reference<TestEntity>> phantomRef,
		Phantom<Catalog<TestChild>> phantomCatalog,
		Phantom<Listing<TestChild>> phantomListing,
		Phantom<SideTable<TestChild, String>> phantomSideTable
	) implements Entity {
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
	}

	@With
	@FieldNameConstants
	public record ImplicitRefs(
		Identifier id,
		Reference<ImplicitRefs> reference,
		Reference<TestEntity> enclosingRef,
		@Self Reference<ImplicitRefs> reference2,
		@Enclosing Reference<TestEntity> enclosingRef2
	) implements Entity {
		public ImplicitRefs(Identifier id, @Self Reference<ImplicitRefs> reference, @Enclosing Reference<TestEntity> enclosingRef, Reference<ImplicitRefs> reference2, Reference<TestEntity> enclosingRef2) {
			this.id = id;
			this.reference = reference;
			this.enclosingRef = enclosingRef;
			this.reference2 = reference2;
			this.enclosingRef2 = enclosingRef2;
		}
	}

	public interface Variant extends VariantCase {
		@Override default String tag() {
			return "variant1";
		}

		@VariantCaseMap
		MapValue<Type> CASES = MapValue.singleton("variant1", VariantCase1.class);
	}

	public record VariantCase1(String stringField) implements Variant { }

	protected static Bosk<TestRoot> setUpBosk(DriverFactory<TestRoot> driverFactory) {
		return new Bosk<TestRoot>(boskName(1), TestRoot.class, AbstractRoundTripTest::initialRoot, driverFactory, Bosk.simpleRegistrar());
	}

	protected static TestRoot initialRoot(Bosk<TestRoot> bosk) {
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
			TaggedUnion.of(new VariantCase1("variantCase1String")));
		return new TestRoot(
			Identifier.from("root"),
			Catalog.of(entity),
			new StringListValueSubclass("One", "Two"),
			MapValue.fromFunction(asList("key1", "key2"), k ->k + "_value"));
	}

}
