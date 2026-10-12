package works.bosk.libtesting.fuzz;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import works.bosk.testing.drivers.fuzz.OperationKind;
import works.bosk.testing.drivers.fuzz.OperationScript;

/**
 * Produces new operation scripts by perturbing existing ones.
 *
 * <p>Where {@link works.bosk.testing.drivers.fuzz.OperationSequenceGenerator} builds sequences from
 * scratch, this reuses sequences that are already known to be interesting and makes small changes
 * to them, on the theory that bugs often hide near working behaviour. Perturbations are chosen so
 * that the result is still a well-formed script: operations may be dropped, duplicated, swapped,
 * or interleaved with a flush, and a conditional operation's precondition may be made to match or
 * to fail. Because whole entities are addressed by path, a perturbed script remains replayable
 * against any bosk with the same schema.
 */
public final class ScriptMutator {

	/**
	 * @return a perturbed copy of {@code script}, with a name that indicates it was mutated
	 */
	public static OperationScript mutate(OperationScript script, Random random) {
		if (!(script instanceof SpecOperationScript specScript)) {
			throw new IllegalArgumentException("Cannot mutate a script of type " + script.getClass().getName());
		}
		List<OperationSpec> specs = new ArrayList<>(specScript.operations());
		int numMutations = 1 + random.nextInt(3);
		for (int i = 0; i < numMutations; i++) {
			applyMutation(specs, random);
		}
		return new SpecOperationScript(script.name() + "-mutated", List.copyOf(specs));
	}

	private static void applyMutation(List<OperationSpec> specs, Random random) {
		switch (Mutation.values()[random.nextInt(Mutation.values().length)]) {
			case DROP -> {
				if (specs.size() > 1) {
					specs.remove(random.nextInt(specs.size()));
				}
			}
			case DUPLICATE -> {
				if (!specs.isEmpty()) {
					OperationSpec original = specs.get(random.nextInt(specs.size()));
					specs.add(random.nextInt(specs.size() + 1), original);
				}
			}
			case SWAP -> {
				if (specs.size() > 1) {
					int a = random.nextInt(specs.size());
					int b = random.nextInt(specs.size());
					specs.set(a, specs.set(b, specs.get(a)));
				}
			}
			case INSERT_FLUSH -> specs.add(random.nextInt(specs.size() + 1), new OperationSpec(OperationKind.FLUSH, null, null, null, null));
			case FLIP_PRECONDITION -> flipPrecondition(specs, random);
		}
	}

	private static void flipPrecondition(List<OperationSpec> specs, Random random) {
		List<Integer> conditionalIndices = new ArrayList<>();
		for (int i = 0; i < specs.size(); i++) {
			OperationKind kind = specs.get(i).kind();
			if (kind == OperationKind.CONDITIONAL_REPLACE || kind == OperationKind.CONDITIONAL_DELETE) {
				conditionalIndices.add(i);
			}
		}
		if (!conditionalIndices.isEmpty()) {
			int index = conditionalIndices.get(random.nextInt(conditionalIndices.size()));
			OperationSpec spec = specs.get(index);
			// Half the time fix the precondition to match the target, half the time break it.
			String requiredValue = random.nextBoolean() ? idFromTarget(spec.target()) : spec.requiredValue() + "!";
			specs.set(index, new OperationSpec(spec.kind(), spec.target(), spec.precondition(), requiredValue, spec.value()));
		}
	}

	private static String idFromTarget(String target) {
		int lastSlash = target.lastIndexOf('/');
		return lastSlash < 0 ? target : target.substring(lastSlash + 1);
	}

	enum Mutation {
		DROP,
		DUPLICATE,
		SWAP,
		INSERT_FLUSH,
		FLIP_PRECONDITION
	}
}
