package works.bosk.jackson;

import java.io.IOException;
import java.lang.reflect.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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
	final ObjectMapper mapper;
	final JsonNodeSurgeon surgeon;
	protected JsonNode currentRoot;
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
	}

	@Override
	public synchronized StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		StateTreeNode result = downstream.initialRoot(rootType);
		currentRoot = mapper.convertValue(result, JsonNode.class);
		traceCurrentState("After initialRoot");
		return result;
	}

	@Override
	public synchronized <T> void submitReplacement(Reference<T> target, T newValue) {
		traceCurrentState("Before submitReplacement");
		doReplacement(surgeon.nodeInfo(currentRoot, target), target.path().lastSegment(), newValue);
		downstream.submitReplacement(target, newValue);
		traceCurrentState("After submitReplacement");
	}

	@Override
	public synchronized <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		traceCurrentState("Before submitConditionalReplacement");
		if (requiredValue.toString().equals(surgeon.valueNode(currentRoot, precondition).textValue())) {
			doReplacement(surgeon.nodeInfo(currentRoot, target), target.path().lastSegment(), newValue);
		}
		downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
		traceCurrentState("After submitConditionalReplacement");
	}

	@Override
	public synchronized <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		traceCurrentState("Before submitConditionalCreation");
		if (surgeon.valueNode(currentRoot, target) == null) {
			doReplacement(surgeon.nodeInfo(currentRoot, target), target.path().lastSegment(), newValue);
		}
		downstream.submitConditionalCreation(target, newValue);
		traceCurrentState("After submitConditionalCreation");
	}

	@Override
	public synchronized <T> void submitDeletion(Reference<T> target) {
		traceCurrentState("Before submitDeletion");
		surgeon.deleteNode(surgeon.nodeInfo(currentRoot, target));
		downstream.submitDeletion(target);
		traceCurrentState("After submitDeletion");
	}

	@Override
	public synchronized <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		traceCurrentState("Before submitConditionalDeletion");
		if (requiredValue.toString().equals(surgeon.valueNode(currentRoot, precondition).textValue())) {
			surgeon.deleteNode(surgeon.nodeInfo(currentRoot, target));
		}
		downstream.submitConditionalDeletion(target, precondition, requiredValue);
		traceCurrentState("After submitConditionalDeletion");
	}

	@Override
	public synchronized void flush() throws IOException, InterruptedException {
		traceCurrentState("Before flush");
		downstream.flush();
	}

	private <T> void doReplacement(NodeInfo nodeInfo, String lastSegment, T newValue) {
		JsonNode replacement = surgeon.replacementNode(nodeInfo, lastSegment, () -> mapper.convertValue(newValue, JsonNode.class));
		if (nodeInfo.replacementLocation() instanceof Root) {
			currentRoot = replacement;
		} else {
			surgeon.replaceNode(nodeInfo, replacement);
		}
	}

	void traceCurrentState(String description) {
		if (LOGGER.isTraceEnabled()) {
			LOGGER.trace("State {} {}:\n{}", ++updateNumber, description, currentRoot.toPrettyString());
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(JsonNodeDriver.class);
}
