package works.bosk;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collector;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.pcollections.OrderedPMap;
import org.pcollections.OrderedPSet;

import static java.util.Objects.requireNonNull;

@EqualsAndHashCode
@RequiredArgsConstructor(access=AccessLevel.PRIVATE)
public final class SideTable<K extends Entity, V> implements EnumerableByIdentifier<V> {
	@Getter
	private final CatalogReference<K> domain;
	private final OrderedPMap<Identifier, V> valuesById;

	public V get(Identifier id) { return valuesById.get(id); }
	public V get(K key)         { return valuesById.get(key.id()); }

	public boolean hasID(Identifier id) { return valuesById.containsKey(id); }
	public boolean hasKey(K key)        { return valuesById.containsKey(key.id()); }

	public boolean isEmpty() { return valuesById.isEmpty(); }
	public int size() { return valuesById.size(); }
	public List<Identifier> ids() { return List.copyOf(valuesById.keySet()); }
	public Listing<K> keys() { return new Listing<>(domain, OrderedPSet.from(valuesById.keySet())); }
	public Collection<V> values() { return valuesById.values(); }
	public Set<Entry<Identifier, V>> idEntrySet() { return valuesById.entrySet(); }

	public Map<Identifier, V> asMap() { return valuesById; }

	public Stream<Entry<K, V>> valueEntryStream() {
		AddressableByIdentifier<K> domainValue = domain.value();
		return idEntrySet().stream().map(e -> new SimpleImmutableEntry<>(
			domainValue.get(e.getKey()),
			e.getValue()));
	}

	/**
	 * Note that this requires a read context, and for nonexistent keys,
	 * this will pass null as the key value.
	 *
	 * @see #forEachID
	 */
	public void forEachValue(BiConsumer<? super K, ? super V> action) {
		AddressableByIdentifier<K> domainValue = domain.value();
		valuesById.forEach((id, value) -> action.accept(domainValue.get(id), value));
	}

	public void forEachID(BiConsumer<Identifier, ? super V> action) {
		valuesById.forEach(action);
	}

	public SideTable<K,V> with(Identifier id, V value) {
		return new SideTable<>(this.domain, valuesById.plus(id, value));
	}

	public SideTable<K,V> with(K key, V value) {
		return this.with(key.id(), value);
	}

	public SideTable<K,V> updatedWith(Identifier id, Supplier<V> valueIfAbsent, UnaryOperator<V> valueIfPresent) {
		V existing = valuesById.get(id);
		V replacement;
		if (existing == null) {
			replacement = valueIfAbsent.get();
		} else {
			replacement = valueIfPresent.apply(existing);
		}
		return this.with(id, replacement);
	}

	public SideTable<K,V> without(Identifier id) {
		return new SideTable<>(this.domain, valuesById.minus(id));
	}

	public SideTable<K,V> without(K key) {
		return this.without(key.id());
	}

	/**
	 * If you get type inference errors with this one, try specifying the value class
	 * with {@link #empty(Reference, Class)}.
	 */
	public static <KK extends Entity,VV> SideTable<KK,VV> empty(Reference<Catalog<KK>> domain) {
		return new SideTable<>(CatalogReference.from(domain), OrderedPMap.empty());
	}

	public static <KK extends Entity,VV> SideTable<KK,VV> empty(Reference<Catalog<KK>> domain, Class<VV> ignored) {
		return empty(domain);
	}

	public static <KK extends Entity, VV> SideTable<KK,VV> of(Reference<Catalog<KK>> domain, Identifier id, VV value) {
		return new SideTable<>(CatalogReference.from(domain), OrderedPMap.singleton(id, value));
	}

	public static <KK extends Entity, VV> SideTable<KK,VV> of(Reference<Catalog<KK>> domain, KK key, VV value) {
		return of(domain, key.id(), value);
	}

	/**
	 * Use {@link #copyOf}.
	 */
	@Deprecated(forRemoval = true)
	public static <KK extends Entity,VV> SideTable<KK,VV> fromOrderedMap(Reference<Catalog<KK>> domain, Map<Identifier, VV> contents) {
		return copyOf(domain, contents);
	}

	public static <KK extends Entity,VV> SideTable<KK,VV> copyOf(Reference<Catalog<KK>> domain, Map<Identifier, VV> contents) {
		OrderedPMap<Identifier, VV> map = OrderedPMap.from(contents);
		map.forEach((k,v) -> {
			requireNonNull(k);
			requireNonNull(v);
		});
		return new SideTable<>(CatalogReference.from(domain), map);
	}

	public static <KK extends Entity,VV> SideTable<KK,VV> fromFunction(Reference<Catalog<KK>> domain, Stream<Identifier> keyIDs, Function<Identifier, VV> function) {
		LinkedHashMap<Identifier,VV> map = new LinkedHashMap<>();
		keyIDs.forEachOrdered(id -> {
			VV existing = map.put(id, function.apply(id));
			if (existing != null) {
				throw new IllegalArgumentException("Multiple entries with id \"" + id + "\"");
			}
		});
		return new SideTable<>(CatalogReference.from(domain), OrderedPMap.from(map));
	}

	public static <T, KK extends Entity, VV> Collector<T, ?, SideTable<KK, VV>> toSideTable(
		Reference<Catalog<KK>> domain,
		Function<? super T, Identifier> idMapper,
		Function<? super T, ? extends VV> valueMapper
	) {
		class Accumulator {
			OrderedPMap<Identifier, VV> map = OrderedPMap.empty();
			void accumulate(T item) { map = map.plus(idMapper.apply(item), valueMapper.apply(item)); }
			Accumulator combine(Accumulator other) { map = map.plusAll(other.map); return this; }
			SideTable<KK, VV> finish() { return SideTable.copyOf(domain, map); }
		}
		return Collector.of(
			Accumulator::new,
			Accumulator::accumulate,
			Accumulator::combine,
			Accumulator::finish
		);
	}

	public static <KK extends Entity,VV> SideTable<KK,VV> fromEntries(Reference<Catalog<KK>> domain, Stream<Entry<Identifier, VV>> entries) {
		LinkedHashMap<Identifier,VV> map = new LinkedHashMap<>();
		entries.forEachOrdered(entry -> {
			Identifier id = entry.getKey();
			VV value = entry.getValue();
			VV existing = map.put(id, value);
			if (existing != null) {
				throw new IllegalArgumentException("Multiple entries with id \"" + id + "\"");
			}
		});
		return new SideTable<>(CatalogReference.from(domain), OrderedPMap.from(map));
	}

	public static <KK extends Entity, VV> Builder<KK, VV> builder(Reference<Catalog<KK>> domain) {
		return new Builder<>(CatalogReference.from(domain));
	}

	public static class Builder<KK extends Entity, VV> {
		private final CatalogReference<KK> domain;
		private OrderedPMap<Identifier, VV> map = OrderedPMap.empty();

		public Builder(CatalogReference<KK> domain) {
			this.domain = domain;
		}

		public Builder<KK, VV> put(Identifier id, VV value) {
			map = map.plus(id, value);
			return this;
		}

		public Builder<KK, VV> put(KK key, VV value) {
			return put(key.id(), value);
		}

		public SideTable<KK, VV> build() {
			return new SideTable<>(domain, map);
		}
	}

	@Override
	public String toString() {
		return domain + "/" + valuesById;
	}

}
