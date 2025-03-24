package works.bosk.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import works.bosk.Bosk;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.RootReference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

import static java.util.Objects.requireNonNull;

/**
 * A pool of bosks arranged such that submitting an update to any of them submits to all of them.
 * New bosks can be added any time by initializing them with {@link #driverFactory()} in their driver stack.
 * <p>
 * Note that this isn't used for true distributed replica sets with one bosk in each JVM process,
 * but rather for constructing a local replica set within a single JVM process.
 * <p>
 * The primary way to use this class is to instantiate a {@code new ReplicaSet()},
 * then construct any number of bosks using {@link #driverFactory()}.
 * All the resulting bosks will be in the replica set, and more can be added dynamically.
 * <p>
 * There are also some factory methods that simplify some special use cases.
 *
 * <ol>
 *     <li>
 *         Use {@link #mirroringTo} to perform the same changes on some number
 *         of secondary bosks with the same root type.
 *     </li>
 *     <li>
 *         Use {@link #redirectingTo} just to get a driver that can accept references to the wrong bosk.
 *     </li>
 * </ol>
 */
public class ReplicaSet<R extends StateTreeNode> {
	final Queue<Replica<R>> replicas = new ConcurrentLinkedQueue<>();

	/**
	 * The bosk whose state is returned by {@link BroadcastDriver#initialRoot}.
	 */
	final AtomicReference<Replica<R>> primary = new AtomicReference<>(null);

	/**
	 * Whether {@link BoskDriver#initialRoot} has been called for the primary replica.
	 */
	final AtomicBoolean isInitialized = new AtomicBoolean(false);

	/**
	 * We can actually use the same driver for every bosk in the replica set
	 * because they all do the same thing!
	 */
	final BroadcastDriver broadcastDriver = new BroadcastDriver();

	public DriverFactory<R> driverFactory() {
		return (b,d) -> {
			Replica<R> replica = new Replica<>(b, d);
			primary.compareAndSet(null, replica);
			replicas.add(replica);
			return broadcastDriver;
		};

		/*
		 * Note: there's a subtle and interesting thing going on here.
		 * Most driver factories can accept a bosk and a driver of any matching root type,
		 * but this one requires a root type of R specifically. This enforces the rule that
		 * all the bosks in a replica set must have the same root type.
		 */
	}

	/**
	 * Causes updates to be applied to {@code mirrors} before proceeding to the downstream driver.
	 * <p>
	 * This is an asymmetric setup where updates to {@code mirrors} do not update the primary.
	 * (There's really no other option if you want to mirror changes to bosks that have already
	 * been initialized, because it's impossible to alter those bosks' drivers.)
	 * <p>
	 * Assuming the returned factory is only used once, this has the effect of creating
	 * a fixed replica set to which new replicas can't be added dynamically;
	 * however, the returned factory could be used multiple times.
	 * <p>
	 * It may seem counterintuitive that mirrors receive updates before the "main" bosk,
	 * but experience shows that the alternative is even more confusing.
	 * This way, placing {@code mirroringTo} in a {@link DriverStack} causes the mirroring
	 * to occur at that location in the stack, which is easy to understand.
	 */
	@SafeVarargs
	public static <RR extends StateTreeNode> DriverFactory<RR> mirroringTo(Bosk<RR>... mirrors) {
		var replicaSet = new ReplicaSet<RR>();
		for (var m: mirrors) {
			BoskDriver downstream = m.driver();
			replicaSet.replicas.add(new Replica<>(m, downstream));
		}
		return replicaSet.driverFactory();
	}

	/**
	 * Causes updates to be applied only to <code>other</code>.
	 * The resulting driver can accept references to a different bosk
	 * with the same root type.
	 * <p>
	 * Note that there is no "primary" bosk in this case.
	 * The returned driver will send updates only to the {@code other} bosk.
	 * This is much like a {@link NoOpDriver} except that this one can accept
	 * references to a different bosk.
	 */
	public static <RR extends StateTreeNode> BoskDriver redirectingTo(Bosk<RR> other) {
		// A ReplicaSet with only the one replica
		return new ReplicaSet<RR>()
			.driverFactory().build(
				other,
				other.driver()
			);
	}

	final class BroadcastDriver implements BoskDriver {
		/**
		 * @return the <em>current state</em> of the replica set, which is the state of its primary
		 * as obtained by {@link Bosk#supersedingReadContext()}.
		 */
		@Override
		public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
			assert !replicas.isEmpty(): "Replicas must be added during by the driver factory before the drivers are used";
			var primary = requireNonNull(ReplicaSet.this.primary.get());
			if (isInitialized.getAndSet(true)) {
				// Secondary replicas should take their initial state from the primary.
				//
				// We assume the primary's constructor has finished by this point,
				// which is true if the bosks are constructed in the same order as their drivers.
				// This should be a safe assumption--some shenanigans would be required
				// to violate this--but unfortunately we have no way to verify it here,
				// because at this point in the code, we cannot tell which replica we're initializing.
				try (var __ = primaryReadContext(primary)) {
					return primary.boskInfo().rootReference().value();
				}
			} else {
				// The first time this is called, we assume it's for the primary replica.
				return primary.driver().initialRoot(rootType);
			}
		}

		private static <R extends StateTreeNode> Bosk<R>.ReadContext primaryReadContext(Replica<R> primary) {
			try {
				// We use supersedingReadContext here on the assumption that if the user,
				// for some reason, created a secondary replica in the midst
				// of a ReadContext on the primary, they would still want that secondary
				// to see the "real" state.
				return primary.boskInfo.bosk().supersedingReadContext();
			} catch (IllegalStateException e) {
				// You have engaged in the aforementioned shenanigans.
				throw new IllegalStateException("Unable to acquire primary read context; multiple replicas are being initialized simultaneously", e);
			}
		}

		@Override
		public <T> void submitReplacement(Reference<T> target, T newValue) {
			replicas.forEach(r -> r.driver
				.submitReplacement(
					r.correspondingReference(target), newValue));
		}

		@Override
		public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
			replicas.forEach(r -> r.driver
				.submitConditionalReplacement(
					r.correspondingReference(target), newValue,
					r.correspondingReference(precondition), requiredValue));
		}

		@Override
		public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
			replicas.forEach(r -> r.driver
				.submitConditionalCreation(
					r.correspondingReference(target), newValue));
		}

		@Override
		public <T> void submitDeletion(Reference<T> target) {
			replicas.forEach(r -> r.driver
				.submitDeletion(
					r.correspondingReference(target)));
		}

		@Override
		public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
			replicas.forEach(r -> r.driver
				.submitConditionalDeletion(
					r.correspondingReference(target),
					r.correspondingReference(precondition), requiredValue));
		}

		@Override
		public void flush() throws IOException, InterruptedException {
			for (var r : replicas) {
				r.driver().flush();
			}
		}

	}

	/**
	 * @param driver the downstream driver to use for a given replica
	 *               (not the driver that would be returned from {@link Bosk#driver()},
	 *               because that would lead to infinite recursion.)
	 */
	record Replica<R extends StateTreeNode>(
		BoskInfo<R> boskInfo,
		BoskDriver driver
	) {
		public RootReference<R> rootReference() {
			return boskInfo.rootReference();
		}

		@SuppressWarnings("unchecked")
		private <T> Reference<T> correspondingReference(Reference<T> original) {
			try {
				return (Reference<T>) rootReference().then(Object.class, original.path());
			} catch (InvalidTypeException e) {
				throw new AssertionError("Every reference should support a target class of Object", e);
			}
		}

	}


}
