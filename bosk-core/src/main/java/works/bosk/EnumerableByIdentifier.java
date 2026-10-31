package works.bosk;

import java.util.Collection;

sealed public interface EnumerableByIdentifier<T> extends AddressableByIdentifier<T> permits Catalog, SideTable {
	/**
	 * @return the identifiers in the order their entries appear in this container;
	 *         the returned collection is unmodifiable
	 */
	Collection<Identifier> ids();
}
