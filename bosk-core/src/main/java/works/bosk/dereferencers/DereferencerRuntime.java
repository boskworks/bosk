package works.bosk.dereferencers;

import java.util.Optional;
import works.bosk.Bosk.NonexistentEntryException;
import works.bosk.Catalog;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.TaggedUnion;
import works.bosk.TaggedUnionCase;

import static works.bosk.ListingEntry.LISTING_ENTRY;

/**
 * Helper methods called by compiled {@link Dereferencer}s.
 *
 * <p>
 * Because compiled code is loaded by a different {@link ClassLoader}, it will
 * appear to be in a different "runtime package" and therefore can't access
 * package-private classes; hence, we must make this public.
 */
public abstract class DereferencerRuntime implements Dereferencer {
	protected static Object invalidWithout(Object notCollection, Reference<?> ref) {
		throw new IllegalArgumentException("Cannot remove " + ref.path() + " from " + notCollection.getClass().getSimpleName());
	}

	protected static Object throwNonexistentEntry(Reference<?> ref) throws NonexistentEntryException {
		throw new NonexistentEntryException(ref.path());
	}

	protected static Object throwCannotReplacePhantom(Reference<?> ref) {
		throw new IllegalArgumentException("Cannot replace phantom " + ref);
	}

	/**
	 * Rebuilds a {@link TaggedUnion} after one of its case's contents has changed.
	 * The new case's tag must match the tag named by the path: changing cases is
	 * done by replacing the union itself, not by replacing a case with a
	 * differently-tagged one. The value is checked before the union, because a case
	 * carrying the wrong tag can't inhabit the path regardless of what the union
	 * currently holds.
	 * <p>
	 * If the tag is right but the union currently holds a different case, the path
	 * is nonexistent, so the update is ignored.
	 *
	 * @param oldUnion the previous union
	 * @param newCase the new contents for the case the path names
	 * @param expectedTag the tag the path refers to
	 */
	protected static Object taggedUnionWith(Object oldUnion, TaggedUnionCase newCase, String expectedTag, Reference<?> ref) throws NonexistentEntryException {
		if (!expectedTag.equals(newCase.tag())) {
			throw new IllegalArgumentException("Cannot replace " + ref + " with a case tagged \""
				+ newCase.tag() + "\"; replace the TaggedUnion to change cases");
		}
		TaggedUnion<?> union = (TaggedUnion<?>) oldUnion;
		if (!expectedTag.equals(union.value().tag())) {
			throw new NonexistentEntryException(ref.path());
		}
		return TaggedUnion.of(newCase);
	}

	protected static Object throwCannotDeleteTaggedUnionCase(Reference<?> ref) {
		throw new IllegalArgumentException("Cannot delete " + ref + ": a TaggedUnion must have exactly one case");
	}

	protected static Object optionalOrThrow(Optional<?> optional, Reference<?> ref) throws NonexistentEntryException {
		return optional.orElseThrow(() -> new NonexistentEntryException(ref.path()));
	}

	protected static Object catalogEntryOrThrow(Catalog<?> catalog, Identifier id, Reference<?> ref) throws NonexistentEntryException {
		Object result = catalog.get(id);
		if (result == null) {
			throw new NonexistentEntryException(ref.path());
		} else {
			return result;
		}
	}

	@SuppressWarnings("SameReturnValue")
	protected static Object listingEntryOrThrow(Listing<?> listing, Identifier id, Reference<?> ref) throws NonexistentEntryException {
		if (listing.containsID(id)) {
			return LISTING_ENTRY;
		} else {
			throw new NonexistentEntryException(ref.path());
		}
	}

	protected static Object listingWith(Listing<?> listing, Identifier id, Object ignored) {
		return listing.withID(id);
	}

	protected static Object sideTableEntryOrThrow(SideTable<?,?> sideTable, Identifier id, Reference<?> ref) throws NonexistentEntryException {
		Object result = sideTable.get(id);
		if (result == null) {
			throw new NonexistentEntryException(ref.path());
		} else {
			return result;
		}
	}

	protected static Object instanceofOrNonexistent(Object object, Class<?> desiredClass, Reference<?> ref) throws NonexistentEntryException {
		if (desiredClass.isInstance(object)) {
			return object;
		} else {
			throw new NonexistentEntryException(ref.path());
		}
	}

	protected static Object tagCheck(TaggedUnionCase taggedUnionCase, String desiredTag, Reference<?> ref) throws NonexistentEntryException {
		if (desiredTag.equals(taggedUnionCase.tag())) {
			return taggedUnionCase;
		} else {
			throw new NonexistentEntryException(ref.path());
		}
	}
}
