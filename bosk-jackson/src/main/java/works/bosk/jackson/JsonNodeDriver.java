package works.bosk.jackson;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;
import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.jackson.JsonNodeSurgeon.NodeInfo;
import works.bosk.jackson.JsonNodeSurgeon.NodeLocation.Root;

/**
 * Maintains an in-memory representation of the bosk state
 * in the form of a tree of {@link JsonNode} objects.
 */
public class JsonNodeDriver implements BoskDriver {
	final BoskDriver downstream;
	final BoskContext context;
	final ObjectMapper mapper;
	final JsonNodeSurgeon surgeon;
	JsonNode contents;
	int updateNumber = 0;

	public static <R extends StateTreeNode> DriverFactory<R> factory(JacksonSerializer jacksonSerializer) {
		return (b,d) -> new JsonNodeDriver(b, d, jacksonSerializer);
	}

	protected JsonNodeDriver(BoskInfo<?> bosk, BoskDriver downstream, JacksonSerializer jacksonSerializer) {
		this.downstream = downstream;
		this.mapper = JsonMapper.builder()
			.addModule(jacksonSerializer.moduleFor(bosk))
			.build();
		this.surgeon = new JsonNodeSurgeon();
		this.context = bosk.context();
	}

	@Override
	public synchronized <R extends StateTreeNode> R initialState(Class<R> rootType) throws InvalidTypeException, IOException, InterruptedException {
		var result = downstream.initialState(rootType);
		contents = mapper.convertValue(result, JsonNode.class);
		traceCurrentState("After initialState");
		return result;
	}

	@Override
	public synchronized <T> void submitReplacement(Reference<T> target, T newValue) {
		traceCurrentState("Before submitReplacement");
		doReplacement(surgeon.nodeInfo(currentRoot(), target), () -> target.path().lastSegment(), newValue);
		downstream.submitReplacement(target, newValue);
		traceCurrentState("After submitReplacement");
	}

	@Override
	public synchronized <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		traceCurrentState("Before submitConditionalReplacement");
		JsonNode root = currentRoot();
		if (preconditionMatches(root, precondition, requiredValue)) {
			doReplacement(surgeon.nodeInfo(root, target), () -> target.path().lastSegment(), newValue);
		}
		downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
		traceCurrentState("After submitConditionalReplacement");
	}

	@Override
	public synchronized <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		traceCurrentState("Before submitConditionalCreation");
		if (surgeon.valueNode(currentRoot(), target) == null) {
			doReplacement(surgeon.nodeInfo(currentRoot(), target), () -> target.path().lastSegment(), newValue);
		}
		downstream.submitConditionalCreation(target, newValue);
		traceCurrentState("After submitConditionalCreation");
	}

	@Override
	public synchronized <T> void submitDeletion(Reference<T> target) {
		traceCurrentState("Before submitDeletion");
		surgeon.deleteNode(surgeon.nodeInfo(currentRoot(), target));
		downstream.submitDeletion(target);
		traceCurrentState("After submitDeletion");
	}

	@Override
	public synchronized <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		traceCurrentState("Before submitConditionalDeletion");
		JsonNode root = currentRoot();
		if (preconditionMatches(root, precondition, requiredValue)) {
			surgeon.deleteNode(surgeon.nodeInfo(root, target));
		}
		downstream.submitConditionalDeletion(target, precondition, requiredValue);
		traceCurrentState("After submitConditionalDeletion");
	}

	@Override
	public synchronized void flush() throws IOException, InterruptedException {
		traceCurrentState("Before flush");
		downstream.flush();
	}

	/**
	 * @return true if the node referenced by <code>precondition</code> exists in
	 * <code>root</code> and has the value <code>requiredValue</code>. A nonexistent
	 * precondition does not match (it does not cause an exception).
	 */
	private boolean preconditionMatches(JsonNode root, Reference<Identifier> precondition, Identifier requiredValue) {
		return root != null
			&& surgeon.valueNode(root, precondition) instanceof StringNode text
			&& Objects.equals(text.asString(), requiredValue.toString());
	}

	private <T> void doReplacement(NodeInfo nodeInfo, Supplier<String> lastSegment, T newValue) {
		if (nodeInfo.replacementLocation() instanceof Root) {
			contents = mapper.convertValue(newValue, JsonNode.class);
		} else {
			JsonNode replacement = surgeon.replacementNode(nodeInfo, lastSegment.get(), () -> mapper.convertValue(newValue, JsonNode.class));
			surgeon.replaceNode(nodeInfo, replacement);
		}
	}

	void traceCurrentState(String description) {
		if (LOGGER.isTraceEnabled()) {
			LOGGER.trace("State {} {}:\n{}", ++updateNumber, description, contentsPrettyString());
		}
	}

	@Nullable JsonNode currentRoot() {
		return contents;
	}


	private String contentsPrettyString() {
		return mapper.convertValue(contents, JsonNode.class).toPrettyString();
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(JsonNodeDriver.class);
}
