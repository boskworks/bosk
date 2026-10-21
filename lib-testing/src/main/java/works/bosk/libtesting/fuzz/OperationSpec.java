package works.bosk.libtesting.fuzz;

import works.bosk.Identifier;
import works.bosk.Path;

import static java.util.Objects.requireNonNull;

/**
 * A detached, path-based description of a single driver operation.
 *
 * <p>Where a {@link works.bosk.testing.drivers.operations.DriverOperation} holds live
 * {@link works.bosk.Reference References} bound to a particular bosk, an {@code OperationSpec} holds
 * {@link Path Paths}. That makes it portable: it can be serialized, stored, and later rebound to any
 * bosk with the same state schema. For the same reason, this is a plausible eventual replacement for
 * {@code DriverOperation} as the payload of a single driver update method.
 *
 * <p>Values are carried as a normal Java type parameter rather than a JSON-specific type, so that
 * the structure stays representation-neutral. When a spec is deserialized, that type is recovered
 * from the target {@link Path} via {@link works.bosk.Reference#targetType()}.
 */
public sealed interface OperationSpec {

	/**
	 * A single {@code { "<path>": <value> }} pair.
	 *
	 * <p>This models one named role of an operation, such as its {@code target} or its
	 * {@code precondition}. Serialized, the role is an object with exactly one member: the
	 * url-encoded path, whose value is the role's value.
	 *
	 * @param path  the url-encodable path the role refers to
	 * @param value the value associated with the path
	 */
	record Role<T>(Path path, T value) {
		public Role {
			requireNonNull(path);
			requireNonNull(value);
		}
	}

	record Replacement<T>(Role<T> target) implements OperationSpec {
		public Replacement {
			requireNonNull(target);
		}
	}

	record ConditionalCreation<T>(Role<T> target) implements OperationSpec {
		public ConditionalCreation {
			requireNonNull(target);
		}
	}

	record ConditionalReplacement<T>(Role<T> target, Role<Identifier> precondition) implements OperationSpec {
		public ConditionalReplacement {
			requireNonNull(target);
			requireNonNull(precondition);
		}
	}

	/**
	 * A deletion has no value, so its target is a bare {@link Path} rather than a {@link Role}.
	 */
	record Deletion(Path target) implements OperationSpec {
		public Deletion {
			requireNonNull(target);
		}
	}

	record ConditionalDeletion(Path target, Role<Identifier> precondition) implements OperationSpec {
		public ConditionalDeletion {
			requireNonNull(target);
			requireNonNull(precondition);
		}
	}

	record Flush() implements OperationSpec {}
}
