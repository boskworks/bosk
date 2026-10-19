package works.bosk.testing.drivers.state;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import works.bosk.Catalog;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.TaggedUnion;
import works.bosk.TaggedUnionCase;
import works.bosk.annotations.TaggedUnionCaseMap;

public record TestEntity(
	Identifier id,
	String string,
	Catalog<TestEntity> catalog,
	Listing<TestEntity> listing,
	SideTable<TestEntity, TestEntity> sideTable,
	SideTable<TestEntity, SideTable<TestEntity, TestEntity>> nestedSideTable,
	TaggedUnion<Variant> variant,
	Optional<TestValues> values,
	Optional<SelfValue> selfValue
) implements Entity {

	public TestEntity withId(Identifier id) {
		return new TestEntity(id, string, catalog, listing, sideTable, nestedSideTable, variant, values, selfValue);
	}

	public TestEntity withString(String string) {
		return new TestEntity(id, string, catalog, listing, sideTable, nestedSideTable, variant, values, selfValue);
	}

	public TestEntity withCatalog(Catalog<TestEntity> catalog) {
		return new TestEntity(id, string, catalog, listing, sideTable, nestedSideTable, variant, values, selfValue);
	}

	public TestEntity withListing(Listing<TestEntity> listing) {
		return new TestEntity(id, string, catalog, listing, sideTable, nestedSideTable, variant, values, selfValue);
	}

	public TestEntity withSideTable(SideTable<TestEntity, TestEntity> sideTable) {
		return new TestEntity(id, string, catalog, listing, sideTable, nestedSideTable, variant, values, selfValue);
	}

	public TestEntity withNestedSideTable(SideTable<TestEntity, SideTable<TestEntity, TestEntity>> nestedSideTable) {
		return new TestEntity(id, string, catalog, listing, sideTable, nestedSideTable, variant, values, selfValue);
	}

	public TestEntity withVariant(TaggedUnion<Variant> variant) {
		return new TestEntity(id, string, catalog, listing, sideTable, nestedSideTable, variant, values, selfValue);
	}

	public TestEntity withValues(Optional<TestValues> values) {
		return new TestEntity(id, string, catalog, listing, sideTable, nestedSideTable, variant, values, selfValue);
	}

	public TestEntity withSelfValue(Optional<SelfValue> selfValue) {
		return new TestEntity(id, string, catalog, listing, sideTable, nestedSideTable, variant, values, selfValue);
	}

	public interface Variant extends TaggedUnionCase {
		@Override
		default String tag() {
			if (this instanceof StringCase) {
				return "string";
			} else {
				return "identifier";
			}
		}

		@TaggedUnionCaseMap
		MapValue<Type> VARIANT_CASE_MAP = MapValue.copyOf(Map.of(
			"string", StringCase.class,
			"identifier", IdentifierCase.class
		));
	}
	public record StringCase(String value) implements Variant {}
	public record IdentifierCase(Identifier value) implements Variant {}

	public static TestEntity empty(Identifier id, Reference<Catalog<TestEntity>> catalogRef) {
		return new TestEntity(id,
			id.toString(),
			Catalog.empty(),
			Listing.empty(catalogRef),
			SideTable.empty(catalogRef),
			SideTable.empty(catalogRef),
			TaggedUnion.of(new StringCase("")),
			Optional.empty(),
			Optional.empty());
	}

	public static final class Fields {
		public static final String id = "id";
		public static final String string = "string";
		public static final String catalog = "catalog";
		public static final String listing = "listing";
		public static final String sideTable = "sideTable";
		public static final String nestedSideTable = "nestedSideTable";
		public static final String variant = "variant";
		public static final String values = "values";
		public static final String selfValue = "selfValue";
	}

}
