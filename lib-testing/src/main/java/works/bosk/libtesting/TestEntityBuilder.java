package works.bosk.libtesting;

import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.TaggedUnion;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;

public class TestEntityBuilder {
	public final Refs refs;

	public interface Refs {
		@ReferencePath("/entities") CatalogReference<AbstractBoskTest.TestEntity> entitiesRef();
		@ReferencePath("/entities/-entity-") Reference<AbstractBoskTest.TestEntity> anyEntity();
		@ReferencePath("/entities/-entity-") Reference<AbstractBoskTest.TestEntity> entityRef(Identifier entity);
		@ReferencePath("/entities/-entity-/children") CatalogReference<AbstractBoskTest.TestChild> childrenRef(Identifier entity);
		@ReferencePath("/entities/-entity-/implicitRefs") Reference<AbstractBoskTest.ImplicitRefs> implicitRefsRef(Identifier entity);
	}

	public TestEntityBuilder(Bosk<AbstractBoskTest.TestRoot> bosk) throws InvalidTypeException {
		this.refs = bosk.buildReferences(Refs.class);
	}

	public CatalogReference<AbstractBoskTest.TestEntity> entitiesRef() { return refs.entitiesRef(); }
	public Reference<AbstractBoskTest.TestEntity> anyEntity() { return refs.anyEntity(); }
	public Reference<AbstractBoskTest.TestEntity> entityRef(Identifier id) { return refs.entityRef(id); }
	public CatalogReference<AbstractBoskTest.TestChild> childrenRef(Identifier entityID) { return refs.childrenRef(entityID); }
	public Reference<AbstractBoskTest.ImplicitRefs> implicitRefsRef(Identifier entityID) { return refs.implicitRefsRef(entityID); }

	public AbstractBoskTest.TestEntity blankEntity(Identifier id, AbstractBoskTest.TestEnum testEnum) {
		return new AbstractBoskTest.TestEntity(id,
			id.toString(),
			testEnum,
			Catalog.empty(),
			Listing.empty(childrenRef(id)),
			SideTable.empty(childrenRef(id)),
			AbstractBoskTest.Phantoms.empty(Identifier.from(id + "_phantoms")),
			AbstractBoskTest.Optionals.empty(Identifier.from(id + "_optionals")),
			new AbstractBoskTest.ImplicitRefs(Identifier.from(id + "_implicitRefs"),
				implicitRefsRef(id), entityRef(id),
				implicitRefsRef(id), entityRef(id)),
			TaggedUnion.of(new AbstractBoskTest.VariantCase1("variantCase1String")));
	}

}
