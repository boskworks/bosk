package works.bosk.util;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;
import org.pcollections.TreePMap;
import works.bosk.BoskContext.Tenant;
import works.bosk.BoskContext.Tenant.Established;
import works.bosk.BoskContext.Tenant.TenantId;
import works.bosk.BoskDriver.EntireState;
import works.bosk.BoskDriver.EntireState.MultiTree;
import works.bosk.BoskDriver.EntireState.SingleTree;
import works.bosk.StateTreeNode;

import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static works.bosk.BoskContext.Tenant.NONE;

/**
 * A data structure that needs separate versions in multitenant situations.
 */
public sealed interface PerTenant<T> {
	T get(Tenant.Established tenant);
	void forEach(BiConsumer<? super Established, ? super T> consumer);
	boolean allMatch(Predicate<? super T> predicate);
	<U> PerTenant<U> map(BiFunction<Tenant.Established, T,U> mapping);

	/**
	 * @throws IllegalArgumentException if this can't be represented as a {@link NoTenant}
	 * because there's not exactly one {@link NoTenant#value() value}.
	 */
	NoTenant<T> asNoTenant(TenantId expectedTenant);

	default <U> PerTenant<U> map(Function<T,U> mapping) {
		return map((_, x) -> mapping.apply(x));
	}

	static <R extends StateTreeNode, V> PerTenant<V> from(EntireState<R> state, Function<R, V> valueMapper) {
		return switch (state) {
			case SingleTree<R>(var root) -> new NoTenant<>(valueMapper.apply(root));
			case MultiTree<R>(var roots)-> roots.entrySet().stream()
				.collect(MultiTenant.withValues(valueMapper));
		};
	}

	/**
	 * Not a multitenant situation: the {@link Tenant} is {@link Tenant#NONE NONE}
	 * and there's exactly one {@code value}.
	 */
	record NoTenant<T>(T value) implements PerTenant<T> {
		@Override
		public T get(Established tenant) {
			if (tenant == NONE) {
				return value;
			} else {
				throw new IllegalStateException("No such tenant: " + tenant);
			}
		}

		@Override
		public void forEach(BiConsumer<? super Established, ? super T> consumer) {
			consumer.accept(NONE, value);
		}

		@Override
		public boolean allMatch(Predicate<? super T> predicate) {
			return predicate.test(value);
		}

		@Override
		public <U> PerTenant<U> map(BiFunction<Established, T, U> mapping) {
			return new NoTenant<>(mapping.apply(NONE, value));
		}

		@Override
		public NoTenant<T> asNoTenant(TenantId expectedTenant) {
			return this;
		}

		public static <TT> NoTenant<TT> just(TT value) {
			return new NoTenant<>(value);
		}
	}

	/**
	 * Multitenant situation: there are zero or more tenants, each with its
	 * own version of the data.
	 * <p>
	 * Tenants are ordered by their ID.
	 */
	record MultiTenant<T>(SortedMap<TenantId, T> values) implements PerTenant<T> {
		public MultiTenant {
			requireNonNull(values);
			// The values should always be a TreePMap so that we can do efficient
			// nondestructive updates, but we don't want to force all users of this
			// library to depend explicitly on the pcollections library.
			if (!(values instanceof TreePMap<TenantId,T>)) {
				values = TreePMap.from(values);
			}
		}

		@Override
		public NoTenant<T> asNoTenant(TenantId expectedTenant) {
			if (values.keySet().equals(Set.of(expectedTenant))) {
				return NoTenant.just(values.get(expectedTenant));
			} else {
				throw new IllegalArgumentException(
					"Expected exactly one tenant: " + expectedTenant + ", but got: " + values.keySet());
			}
		}

		@Override
		public T get(Established tenant) {
			if (tenant instanceof TenantId tenantId) {
				return values.get(tenantId);
			} else {
				throw new IllegalStateException("Invalid tenant: " + tenant);
			}
		}

		@Override
		public void forEach(BiConsumer<? super Established, ? super T> consumer) {
			values.forEach(consumer);
		}

		@Override
		public boolean allMatch(Predicate<? super T> predicate) {
			return values.values().stream().allMatch(predicate);
		}

		@Override
		public <U> PerTenant<U> map(BiFunction<Established, T, U> mapping) {
			return values.entrySet().stream().collect(MultiTenant.multiTenant(
				Entry::getKey,
				e -> mapping.apply(e.getKey(), e.getValue())));
		}

		public static <TT> MultiTenant<TT> empty() {
			return new MultiTenant<>(TreePMap.empty());
		}

		public static <TT> MultiTenant<TT> singleton(TenantId key, TT value) {
			return new MultiTenant<>(TreePMap.singleton(key, value));
		}

		public MultiTenant<T> with(TenantId key, T value) {
			return new MultiTenant<>(treePMap().plus(key, value));
		}

		public MultiTenant<T> withAll(Map<TenantId, T> additionalValues) {
			return new MultiTenant<>(treePMap().plusAll(additionalValues));
		}

		public MultiTenant<T> without(TenantId key) {
			return new MultiTenant<>(treePMap().minus(key));
		}

		public static <IN, OUT> Collector<IN, ?, MultiTenant<OUT>> multiTenant(Function<IN, TenantId> tenantMapper, Function<IN, OUT> valueMapper) {
			class Accumulator {
				TreePMap<TenantId, OUT> map = TreePMap.empty();
				void accumulate(IN e) { map = map.plus(tenantMapper.apply(e), valueMapper.apply(e));  }
				Accumulator combine(Accumulator other) { map = map.plusAll(other.map); return this; }
				MultiTenant<OUT> finish() { return new MultiTenant<>(map); }
			}
			return Collector.of(
				Accumulator::new,
				Accumulator::accumulate,
				Accumulator::combine,
				Accumulator::finish
			);
		}

		public static <IN, OUT> Collector<Entry<TenantId, IN>, ?, MultiTenant<OUT>> withValues(Function<IN, OUT> valueMapper) {
			return multiTenant(Entry::getKey, e -> valueMapper.apply(e.getValue()));
		}

		public static <TT> Collector<Entry<TenantId, TT>, ?, MultiTenant<TT>> collector() {
			return withValues(identity());
		}

		private TreePMap<TenantId, T> treePMap() {
			return (TreePMap<TenantId, T>) values;
		}
	}

}
