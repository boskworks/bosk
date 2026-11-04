package works.bosk.libtesting.fuzz;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import works.bosk.testing.drivers.fuzz.OperationScript;

/**
 * Produces new operation scripts by perturbing existing ones.
 *
 * <p>Where {@link works.bosk.testing.drivers.fuzz.OperationSequenceGenerator} builds sequences from
 * scratch, this reuses sequences that are already known to be interesting and makes small changes
 * to them, on the theory that bugs often hide near working behaviour. Perturbations are chosen so
 * that the result is still a well-formed script: operations may be dropped, duplicated, swapped,
 * or interleaved with a flush, and a conditional operation's precondition may be made to match or
 * to fail.
 *
 * <p>Mutation works on the encoded operation nodes, which is where the paths and values live. The
 * {@link OperationScript} is copied deeply first, so mutating a script never alters its source.
 */
public final class ScriptMutator {
	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	/**
	 * @return a perturbed deep copy of {@code script}, with a name that indicates it was mutated
	 */
	public static OperationScript mutate(OperationScript script, Random random) {
		if (!(script instanceof EncodedOperationScript encoded)) {
			throw new IllegalArgumentException("Cannot mutate a script of type " + script.getClass().getName());
		}
		List<JsonNode> operations = new ArrayList<>(encoded.operations().size());
		for (JsonNode operation : encoded.operations()) {
			operations.add(operation.deepCopy());
		}
		int numMutations = 1 + random.nextInt(3);
		for (int i = 0; i < numMutations; i++) {
			applyMutation(operations, random);
		}
		return new EncodedOperationScript(script.name() + "-mutated", List.copyOf(operations));
	}

	private static void applyMutation(List<JsonNode> operations, Random random) {
		switch (Mutation.values()[random.nextInt(Mutation.values().length)]) {
			case DROP -> {
				if (operations.size() > 1) {
					operations.remove(random.nextInt(operations.size()));
				}
			}
			case DUPLICATE -> {
				if (!operations.isEmpty()) {
					JsonNode original = operations.get(random.nextInt(operations.size()));
					operations.add(random.nextInt(operations.size() + 1), original.deepCopy());
				}
			}
			case SWAP -> {
				if (operations.size() > 1) {
					int a = random.nextInt(operations.size());
					int b = random.nextInt(operations.size());
					operations.set(a, operations.set(b, operations.get(a)));
				}
			}
			case INSERT_FLUSH -> operations.add(random.nextInt(operations.size() + 1), flushNode());
			case FLIP_PRECONDITION -> flipPrecondition(operations, random);
		}
	}

	private static JsonNode flushNode() {
		ObjectNode node = MAPPER.createObjectNode();
		node.set("flush", MAPPER.createObjectNode());
		return node;
	}

	private static void flipPrecondition(List<JsonNode> operations, Random random) {
		List<Integer> conditionalIndices = new ArrayList<>();
		for (int i = 0; i < operations.size(); i++) {
			String tag = singleTag(operations.get(i));
			if (tag.equals("conditionalReplacement") || tag.equals("conditionalDeletion")) {
				conditionalIndices.add(i);
			}
		}
		if (!conditionalIndices.isEmpty()) {
			int index = conditionalIndices.get(random.nextInt(conditionalIndices.size()));
			ObjectNode operationNode = (ObjectNode) operations.get(index);
			ObjectNode payload = (ObjectNode) operationNode.get(singleTag(operationNode));
			ObjectNode precondition = (ObjectNode) payload.get("precondition");
			String path = precondition.properties().iterator().next().getKey();
			// Half the time fix the precondition to match the target, half the time break it.
			String requiredValue = random.nextBoolean() ? idFromPath(path) : precondition.get(path).asString() + "!";
			precondition.put(path, requiredValue);
		}
	}

	private static String singleTag(JsonNode node) {
		return node.properties().iterator().next().getKey();
	}

	private static String idFromPath(String path) {
		int lastSlash = path.lastIndexOf('/');
		return lastSlash < 0 ? path : path.substring(lastSlash + 1);
	}

	enum Mutation {
		DROP,
		DUPLICATE,
		SWAP,
		INSERT_FLUSH,
		FLIP_PRECONDITION
	}
}
