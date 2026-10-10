package works.bosk.testing.drivers.state;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import lombok.With;
import lombok.experimental.FieldNameConstants;
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

@With
@FieldNameConstants
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

}
