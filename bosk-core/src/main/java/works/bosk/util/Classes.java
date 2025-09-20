package works.bosk.util;

import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Entity;
import works.bosk.EnumerableByIdentifier;
import works.bosk.ListValue;
import works.bosk.Listing;
import works.bosk.ListingReference;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.SideTableReference;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;

/**
 * An imperfect, non-idiomatic way to describe complex parameterized types.
 *
 * <p>
 * To represent the type <code>SideTable&lt;X, Listing&lt;Y>></code>, for example,
 * write <code>sideTable(X.class, listing(Y.class))</code>.
 */
@SuppressWarnings({"unchecked","rawtypes","unused"})
public final class Classes {
	public static <E extends Entity> Class<Catalog<E>> catalog(Class<E> entryClass) {
		return (Class)Catalog.class;
	}

	public static <E extends Entity> Class<Listing<E>> listing(Class<E> entryClass) {
		return (Class)Listing.class;
	}

	public static <K extends Entity,V> Class<SideTable<K,V>> sideTable(Class<K> keyClass, Class<V> valueClass) {
		return (Class) SideTable.class;
	}

	public static <E extends Entity> Class<EnumerableByIdentifier<E>> enumerableByIdentifier(Class<E> entryClass) {
		return (Class) EnumerableByIdentifier.class;
	}

	public static <T> Class<Reference<T>> reference(Class<T> targetClass) {
		return (Class)Reference.class;
	}

	public static <E extends Entity> Class<CatalogReference<E>> catalogReference(Class<E> targetClass) {
		return (Class)CatalogReference.class;
	}

	public static <E extends Entity> Class<ListingReference<E>> listingReference(Class<E> targetClass) {
		return (Class)ListingReference.class;
	}

	public static <K extends Entity,V> Class<SideTableReference<K,V>> sideTableReference(Class<K> keyClass, Class<V> valueClass) {
		return (Class) SideTableReference.class;
	}

	public static <E> Class<ListValue<E>> listValue(Class<E> entryClass) {
		return (Class)ListValue.class;
	}

	public static <V> Class<MapValue<V>> mapValue(Class<V> valueClass) {
		return (Class)MapValue.class;
	}

	public static <V extends VariantCase> Class<TaggedUnion<V>> taggedUnion(Class<V> variantCaseClass, String... segments) {
		return (Class)TaggedUnion.class;
	}
}
