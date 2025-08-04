package works.bosk.testing.drivers.state;

import java.util.Optional;
import works.bosk.Catalog;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.SideTable;
import works.bosk.TaggedUnion;
import works.bosk.annotations.Polyfill;

/**
 * A version of {@link TestEntity} where the {@link Optional} {@link TestEntity#values()}
 * field has a polyfill.
 */
public record UpgradeableEntity(
	Identifier id,
	String string,
	Catalog<TestEntity> catalog,
	Listing<TestEntity> listing,
	SideTable<TestEntity, TestEntity> sideTable,
	SideTable<TestEntity, SideTable<TestEntity, TestEntity>> nestedSideTable,
	TaggedUnion<TestEntity.Variant> variant,
	TestValues values
) implements Entity {
	@Polyfill("values")
	public static final TestValues DEFAULT_VALUES = TestValues.blank();
}
