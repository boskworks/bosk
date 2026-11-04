package works.bosk.libtesting.fuzz;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.type.TypeFactory;
import works.bosk.Bosk;
import works.bosk.BoskContext;
import works.bosk.Identifier;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.jackson.JacksonSerializer;
import works.bosk.testing.drivers.fuzz.OperationScript;
import works.bosk.testing.drivers.operations.ConditionalCreation;
import works.bosk.testing.drivers.operations.DriverOperation;
import works.bosk.testing.drivers.operations.FlushOperation;
import works.bosk.testing.drivers.operations.SubmitConditionalDeletion;
import works.bosk.testing.drivers.operations.SubmitConditionalReplacement;
import works.bosk.testing.drivers.operations.SubmitDeletion;
import works.bosk.testing.drivers.operations.SubmitReplacement;
import works.bosk.testing.drivers.state.TestEntity;

/**
 * Reads and writes {@link OperationScript}s as JSON.
 *
 * <p>Each operation is a single-member object whose member name identifies the operation, with a
 * payload that describes its roles:
 *
 * <pre>
 * { "replacement":            { "target": { "/catalog/e/string": "value" } } }
 * { "conditionalCreation":    { "target": { "/catalog/e": { ... } } } }
 * { "conditionalReplacement": { "target":       { "/catalog/e": { ... } },
 *                               "precondition": { "/catalog/e/id": "e" } } }
 * { "deletion":               { "target": "/catalog/e" } }
 * { "conditionalDeletion":    { "target":       "/catalog/e",
 *                               "precondition": { "/catalog/e/id": "e" } } }
 * { "flush":                  {} }
 * </pre>
 *
 * A value-bearing role is an object with a single member, the url-encoded path; a value-less target
 * is a bare path string. The precondition role's value <em>is</em> the required value.
 *
 * <p>The operation structure is written by hand, because a role's member name is data (a path) and
 * cannot be produced by Jackson annotations. Values, by contrast, go through the state-tree
 * serializer: writing is value-driven, while reading recovers each value's type from its role's
 * {@link Reference#targetType()}.
 */
public final class OperationScriptCodec {
	private final ObjectMapper mapper = JsonMapper.builder().build();

	/**
	 * Encodes {@code operations} into a portable script whose operation nodes are bosk-independent.
	 */
	public OperationScript capture(String name, List<DriverOperation> operations, Bosk<TestEntity> bosk) {
		ObjectMapper valueMapper = valueMapperFor(bosk);
		List<JsonNode> nodes = new ArrayList<>(operations.size());
		for (DriverOperation operation : operations) {
			nodes.add(encodeSpec(toSpec(operation), valueMapper));
		}
		return new EncodedOperationScript(name, List.copyOf(nodes));
	}

	public void write(Collection<OperationScript> scripts, OutputStream out) {
		List<EncodedOperationScript> encoded = new ArrayList<>(scripts.size());
		for (OperationScript script : scripts) {
			if (script instanceof EncodedOperationScript encodedScript) {
				encoded.add(encodedScript);
			} else {
				throw new IllegalArgumentException("Cannot write script of type " + script.getClass());
			}
		}
		mapper.writeValue(out, new ScriptFile(encoded));
	}

	public List<OperationScript> read(InputStream in) {
		ScriptFile file = mapper.readValue(in, ScriptFile.class);
		return List.copyOf(file.scripts());
	}

	static ObjectMapper valueMapperFor(Bosk<?> bosk) {
		return JsonMapper.builder().addModule(new JacksonSerializer().moduleFor(bosk)).build();
	}

	static OperationSpec toSpec(DriverOperation operation) {
		if (operation instanceof ConditionalCreation<?> op) {
			return new OperationSpec.ConditionalCreation<>(new OperationSpec.Role<>(op.target().path(), op.newValue()));
		} else if (operation instanceof SubmitReplacement<?> op) {
			return new OperationSpec.Replacement<>(new OperationSpec.Role<>(op.target().path(), op.newValue()));
		} else if (operation instanceof SubmitDeletion<?> op) {
			return new OperationSpec.Deletion(op.target().path());
		} else if (operation instanceof SubmitConditionalReplacement<?> op) {
			return new OperationSpec.ConditionalReplacement<>(
				new OperationSpec.Role<>(op.target().path(), op.newValue()),
				new OperationSpec.Role<>(op.precondition().path(), op.requiredValue()));
		} else if (operation instanceof SubmitConditionalDeletion<?> op) {
			return new OperationSpec.ConditionalDeletion(
				op.target().path(),
				new OperationSpec.Role<>(op.precondition().path(), op.requiredValue()));
		} else if (operation instanceof FlushOperation) {
			return new OperationSpec.Flush();
		} else {
			throw new IllegalArgumentException("Cannot capture operation " + operation);
		}
	}

	static JsonNode encodeSpec(OperationSpec spec, ObjectMapper valueMapper) {
		ObjectNode root = valueMapper.createObjectNode();
		switch (spec) {
			case OperationSpec.Replacement<?> s ->
				root.set("replacement", payloadWithTarget(s.target(), valueMapper));
			case OperationSpec.ConditionalCreation<?> s ->
				root.set("conditionalCreation", payloadWithTarget(s.target(), valueMapper));
			case OperationSpec.ConditionalReplacement<?> s -> {
				ObjectNode payload = payloadWithTarget(s.target(), valueMapper);
				payload.set("precondition", encodeRole(s.precondition(), valueMapper));
				root.set("conditionalReplacement", payload);
			}
			case OperationSpec.Deletion s -> {
				ObjectNode payload = valueMapper.createObjectNode();
				payload.put("target", s.target().urlEncoded());
				root.set("deletion", payload);
			}
			case OperationSpec.ConditionalDeletion s -> {
				ObjectNode payload = valueMapper.createObjectNode();
				payload.put("target", s.target().urlEncoded());
				payload.set("precondition", encodeRole(s.precondition(), valueMapper));
				root.set("conditionalDeletion", payload);
			}
			case OperationSpec.Flush s ->
				root.set("flush", valueMapper.createObjectNode());
		}
		return root;
	}

	private static ObjectNode payloadWithTarget(OperationSpec.Role<?> target, ObjectMapper valueMapper) {
		ObjectNode payload = valueMapper.createObjectNode();
		payload.set("target", encodeRole(target, valueMapper));
		return payload;
	}

	private static ObjectNode encodeRole(OperationSpec.Role<?> role, ObjectMapper valueMapper) {
		ObjectNode result = valueMapper.createObjectNode();
		result.set(role.path().urlEncoded(), valueMapper.convertValue(role.value(), JsonNode.class));
		return result;
	}

	static OperationSpec decodeSpec(JsonNode node, Bosk<TestEntity> bosk, ObjectMapper valueMapper) throws InvalidTypeException {
		Map.Entry<String, JsonNode> entry = singleEntry(node);
		String tag = entry.getKey();
		JsonNode payload = entry.getValue();
		return switch (tag) {
			case "replacement" ->
				new OperationSpec.Replacement<>(decodeRole(required(payload, "target"), bosk, valueMapper));
			case "conditionalCreation" ->
				new OperationSpec.ConditionalCreation<>(decodeRole(required(payload, "target"), bosk, valueMapper));
			case "conditionalReplacement" ->
				new OperationSpec.ConditionalReplacement<>(
					decodeRole(required(payload, "target"), bosk, valueMapper),
					decodeRoleOf(required(payload, "precondition"), bosk, valueMapper, Identifier.class));
			case "deletion" ->
				new OperationSpec.Deletion(Path.parse(required(payload, "target").asString()));
			case "conditionalDeletion" ->
				new OperationSpec.ConditionalDeletion(
					Path.parse(required(payload, "target").asString()),
					decodeRoleOf(required(payload, "precondition"), bosk, valueMapper, Identifier.class));
			case "flush" -> new OperationSpec.Flush();
			default -> throw new IllegalArgumentException("Unknown operation tag \"" + tag + "\"");
		};
	}

	private static <T> OperationSpec.Role<T> decodeRole(JsonNode roleNode, Bosk<TestEntity> bosk, ObjectMapper valueMapper) throws InvalidTypeException {
		return decodeRoleOf(roleNode, bosk, valueMapper, null);
	}

	private static <T> OperationSpec.Role<T> decodeRoleOf(JsonNode roleNode, Bosk<TestEntity> bosk, ObjectMapper valueMapper, Class<?> expectedClass) throws InvalidTypeException {
		Map.Entry<String, JsonNode> entry = singleEntry(roleNode);
		Reference<Object> ref = reference(bosk, entry.getKey());
		JavaType valueType = TYPE_FACTORY.constructType(ref.targetType());
		Object value = valueMapper.convertValue(entry.getValue(), valueType);
		if (expectedClass != null && !expectedClass.isInstance(value)) {
			throw new IllegalArgumentException("Role " + ref.path() + " has value of type " + value.getClass().getSimpleName() + ", expected " + expectedClass.getSimpleName());
		}
		@SuppressWarnings("unchecked")
		T typed = (T) value;
		return new OperationSpec.Role<>(ref.path(), typed);
	}

	@SuppressWarnings("unchecked")
	private static <T> Reference<T> reference(Bosk<TestEntity> bosk, String urlEncodedPath) throws InvalidTypeException {
		return (Reference<T>) bosk.rootReference().then(Object.class, Path.parse(urlEncodedPath));
	}

	static DriverOperation toDriverOperation(OperationSpec spec, Bosk<TestEntity> bosk) throws InvalidTypeException {
		BoskContext.Context context = BoskContext.Context.empty();
		return switch (spec) {
			case OperationSpec.Replacement<?> s ->
				new SubmitReplacement<>(ref(bosk, s.target().path()), s.target().value(), context);
			case OperationSpec.ConditionalCreation<?> s ->
				new ConditionalCreation<>(ref(bosk, s.target().path()), s.target().value(), context);
			case OperationSpec.ConditionalReplacement<?> s ->
				new SubmitConditionalReplacement<>(
					ref(bosk, s.target().path()),
					s.target().value(),
					idRef(bosk, s.precondition().path()),
					s.precondition().value(),
					context);
			case OperationSpec.Deletion s ->
				new SubmitDeletion<>(ref(bosk, s.target()), context);
			case OperationSpec.ConditionalDeletion s ->
				new SubmitConditionalDeletion<>(
					ref(bosk, s.target()),
					idRef(bosk, s.precondition().path()),
					s.precondition().value(),
					context);
			case OperationSpec.Flush s -> new FlushOperation(context);
		};
	}

	@SuppressWarnings("unchecked")
	private static <T> Reference<T> ref(Bosk<TestEntity> bosk, Path path) throws InvalidTypeException {
		return (Reference<T>) bosk.rootReference().then(Object.class, path);
	}

	private static Reference<Identifier> idRef(Bosk<TestEntity> bosk, Path path) throws InvalidTypeException {
		return ref(bosk, path);
	}

	private static Map.Entry<String, JsonNode> singleEntry(JsonNode node) {
		Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
		if (!it.hasNext()) {
			throw new IllegalArgumentException("Expected an object with exactly one member: " + node);
		}
		Map.Entry<String, JsonNode> entry = it.next();
		if (it.hasNext()) {
			throw new IllegalArgumentException("Expected an object with exactly one member: " + node);
		}
		return entry;
	}

	private static JsonNode required(JsonNode node, String field) {
		JsonNode result = node.get(field);
		if (result == null) {
			throw new IllegalArgumentException("Missing \"" + field + "\" in " + node);
		}
		return result;
	}

	public record ScriptFile(List<EncodedOperationScript> scripts) {}

	private static final TypeFactory TYPE_FACTORY = TypeFactory.createDefaultInstance();
}
