package works.bosk;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.stream.Stream;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.pcollections.OrderedPMap;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableCollection;
import static java.util.Collections.unmodifiableMap;
import static java.util.LinkedHashMap.newLinkedHashMap;
import static java.util.Objects.requireNonNull;
import static lombok.AccessLevel.PRIVATE;

/**
 * An ordered collection of entities included by value. Mainly useful to represent
 * one-to-many containment relationships in the Bosk state tree, but also occasionally
 * handy as a data structure on its own.
 *
 * <p>
 * Behaves like a {@link LinkedHashMap}, except immutable, and we automatically
 * know the key for each entry: its {@link Entity#id}.
 *
 * <p>
 * Because a <code>Catalog</code> <em>contains</em> its entries, a {@link Bosk.ReadContext}
 * is not required to access them.
 *
 * @author pdoyle
 *
 */
@RequiredArgsConstructor(access=PRIVATE)
@EqualsAndHashCode
public final class Catalog<E extends Entity> implements Iterable<E>, EnumerableByIdentifier<E> {
	private final OrderedPMap<Identifier, E> contents;

	public int size() { return contents.size(); }

	public boolean isEmpty() { return contents.isEmpty(); }

	@Override
	public E get(Identifier key) {
		return contents.get(requireNonNull(key));
	}

	@Override
	public List<Identifier> ids() {
		return List.copyOf(contents.keySet());
	}

	/**
	 * @return an unmodifiable {@link Collection} containing all the entries
	 * of this catalog, in order
	 */
	public Collection<E> asCollection() {
		return unmodifiableCollection(contents.values());
	}

	/**
	 * @return an unmodifiable {@link Map} containing all the entries
	 * of this catalog, in order
	 */
	public Map<Identifier, E> asMap() {
		return unmodifiableMap(contents);
	}

	@Override
	public Iterator<E> iterator() {
		return contents.values().iterator();
	}

	public Stream<Identifier> idStream() {
		return contents.keySet().stream();
	}

	public Stream<E> stream() {
		return contents.values().stream();
	}

	@Override
	public Spliterator<E> spliterator() {
		// Note that we could add DISTINCT, IMMUTABLE and NONNULL to the
		// characteristics if it turns out to be worth the trouble.  Similar for idStream.
		return contents.values().spliterator();
	}

	public boolean containsID(Identifier key) {
		return get(key) != null;
	}

	public boolean containsAllIDs(Stream<Identifier> keys) {
		return keys.allMatch(this::containsID);
	}

	public boolean containsAllIDs(Iterable<Identifier> keys) {
		for (Identifier key: keys) {
			if (!containsID(key)) {
				return false;
			}
		}
		return true;
	}

	public boolean contains(E entity) {
		return containsID(entity.id());
	}

	public boolean containsAll(Stream<E> entities) {
		return entities.allMatch(this::contains);
	}

	public boolean containsAll(Iterable<E> entities) {
		for (E entity: entities) {
			if (!contains(entity)) {
				return false;
			}
		}
		return true;
	}

	public static <TT extends Entity> Catalog<TT> empty() {
		return new Catalog<>(OrderedPMap.empty());
	}

	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <TT extends Entity> Catalog<TT> of(TT... entities) {
		return of(asList(entities));
	}

	public static <TT extends Entity> Catalog<TT> of(Stream<TT> entities) {
		return of(entities.toList());
	}

	public static <TT extends Entity> Catalog<TT> of(Collection<TT> entities) {
		Map<Identifier, TT> newValues = newLinkedHashMap(entities.size());
		for (TT entity: entities) {
			TT old = newValues.put(requireNonNull(entity.id()), entity);
			if (old != null) {
				throw new IllegalArgumentException("Multiple entities with id " + old.id());
			}
		}
		return new Catalog<>(OrderedPMap.from(newValues));
	}

	public Catalog<E> with(E entity) {
		return new Catalog<>(contents.plus(entity.id(), entity));
	}

	public Catalog<E> withAll(Stream<E> entities) {
		Map<Identifier, E> newValues = new LinkedHashMap<>();
		entities.forEachOrdered(e -> newValues.put(e.id(), e));
		return new Catalog<>(contents.plusAll(newValues));
	}

	public Catalog<E> without(E entity) {
		return this.without(entity.id());
	}

	public Catalog<E> without(Identifier id) {
		return new Catalog<>(contents.minus(id));
	}

	@Override
	public String toString() {
		return contents.toString();
	}

}
