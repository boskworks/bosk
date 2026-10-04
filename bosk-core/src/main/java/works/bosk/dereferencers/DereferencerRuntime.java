package works.bosk.dereferencers;

import java.util.Optional;
import works.bosk.Bosk.NonexistentEntryException;
import works.bosk.Catalog;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.Reference;
import works.bosk.SideTable;
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

	protected static Object throwCannotReplaceTaggedUnionCase(Reference<?> ref) {
		throw new IllegalArgumentException("Cannot replace TaggedUnionCase inside TaggedUnion " + ref);
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
