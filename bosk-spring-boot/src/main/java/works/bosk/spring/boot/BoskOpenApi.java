package works.bosk.spring.boot;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.ListValue;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.MapValue;
import works.bosk.Path;
import works.bosk.Phantom;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.StateTreeSerializer;
import works.bosk.TaggedUnion;
import works.bosk.annotations.Polyfill;
import works.bosk.exceptions.InvalidTypeException;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static works.bosk.ReferenceUtils.parameterType;
import static works.bosk.ReferenceUtils.rawClass;
import static works.bosk.ReferenceUtils.resolveTypeVariables;

/**
 * Generates an OpenAPI 3.1 document describing the maintenance endpoints.
 * <p>
 * The document describes the JSON representation produced by {@link works.bosk.jackson.JacksonSerializer}.
 * Every node in the state tree becomes a path, and each path documents the operations that
 * {@link MaintenanceEndpoints} supports for that node. A node's JSON value embeds its children,
 * so the same data can appear at more than one path; for example, a record field, a container
 * entry, and a tagged-union case also appear within their parent's value.
 * <p>
 * Paths that pass through a tagged-union case are read-only, because Bosk does not permit
 * replacing or deleting a value inside a {@link TaggedUnion}. To change the case, replace the
 * union itself.
 * <p>
 * Implicit {@code @Self} and {@code @Enclosing} references do not appear in a node's JSON, but
 * they remain readable as paths. A {@link Phantom} does not appear in the JSON either, and its
 * path is always empty.
 */
final class BoskOpenApi {
	private BoskOpenApi() {
	}

	/**
	 * @param bosk the Bosk whose state tree is described
	 * @param maintenancePath the path at which {@link MaintenanceEndpoints} is mounted
	 * @param contextPath the servlet context path, or an empty string
	 * @return the OpenAPI document as a Jackson JSON tree
	 */
	static ObjectNode documentFor(Bosk<?> bosk, String maintenancePath, String contextPath) {
		return new Generator(bosk, normalizePath(maintenancePath), contextPath).document();
	}

	private static String normalizePath(String path) {
		if (path.isEmpty() || "/".equals(path)) {
			return "/";
		} else if (path.startsWith("/")) {
			return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
		} else {
			return path.endsWith("/") ? "/" + path.substring(0, path.length() - 1) : "/" + path;
		}
	}

	private enum NodeKind {
		ROOT,
		RECORD_FIELD,
		OPTIONAL_FIELD,
		CONTAINER_ENTRY,
		IMPLICIT_REFERENCE,
		PHANTOM,
		CASE;

		boolean puttable() {
			return this == ROOT || this == RECORD_FIELD || this == OPTIONAL_FIELD || this == CONTAINER_ENTRY;
		}

		boolean deletable() {
			return this == OPTIONAL_FIELD || this == CONTAINER_ENTRY || this == PHANTOM;
		}
	}

	private static final class Generator {
		private final Bosk<?> bosk;
		private final String basePath;
		private final String contextPath;

		private final ObjectNode paths = JsonNodeFactory.instance.objectNode();
		private final Set<String> operationIds = new HashSet<>();
		private final Map<Type, String> schemaNames = new HashMap<>();
		private final Map<String, Type> typesBySchemaName = new HashMap<>();
		private final ObjectNode schemas = JsonNodeFactory.instance.objectNode();
		private final Deque<Type> schemaQueue = new ArrayDeque<>();
		private final Set<Type> queuedSchemaTypes = new HashSet<>();

		Generator(Bosk<?> bosk, String basePath, String contextPath) {
			this.bosk = bosk;
			this.basePath = basePath;
			this.contextPath = contextPath;
		}

		ObjectNode document() {
			walkPaths(
				Path.empty(),
				basePath,
				List.of(),
				NodeKind.ROOT,
				false,
				new HashSet<>());

			while (!schemaQueue.isEmpty()) {
				Type type = schemaQueue.removeFirst();
				schemas.set(schemaName(type), buildRecordSchema(type));
			}

			ObjectNode document = JsonNodeFactory.instance.objectNode();
			document.put("openapi", "3.1.0");
			document.set("info", map(
				"title", "Bosk maintenance API",
				"version", version(),
				"description", DESCRIPTION_TEMPLATE.replace("{name}", bosk.name())));
			if (!contextPath.isEmpty() && !"/".equals(contextPath)) {
				document.set("servers", list(map("url", contextPath)));
			}
			document.set("tags", list(map(
				"name", "bosk-maintenance",
				"description", "Direct JSON access to the Bosk state tree")));
			document.set("paths", paths);
			if (!schemas.isEmpty()) {
				document.set("components", map("schemas", schemas));
			}
			return document;
		}

		private void walkPaths(
			Path boskPath,
			String openapiPath,
			List<ObjectNode> pathParameters,
			NodeKind kind,
			boolean readOnly,
			Set<Type> ancestors
		) {
			Reference<?> reference;
			try {
				reference = boskPath.isEmpty()
					? bosk.rootReference()
					: bosk.rootReference().then(Object.class, boskPath);
			} catch (InvalidTypeException e) {
				LOGGER.warn("Skipping invalid path \"{}\" while generating the OpenAPI document", boskPath, e);
				return;
			}
			Type actualType = reference.targetType();
			addPath(openapiPath, pathParameters, reference, actualType, kind, readOnly);
			if (kind == NodeKind.PHANTOM) {
				// A Phantom's target type is its contents, but the contents never exist.
				return;
			}

			Class<?> rawType = rawClass(actualType);
			if (Catalog.class.isAssignableFrom(rawType)) {
				Type entryType = parameterType(actualType, Catalog.class, 0);
				walkContainerEntry(boskPath, openapiPath, pathParameters, entryType, readOnly, ancestors);
			} else if (SideTable.class.isAssignableFrom(rawType)) {
				Type keyType = parameterType(actualType, SideTable.class, 0);
				walkContainerEntry(boskPath, openapiPath, pathParameters, keyType, readOnly, ancestors);
			} else if (Listing.class.isAssignableFrom(rawType)) {
				Type entryType = parameterType(actualType, Listing.class, 0);
				walkContainerEntry(boskPath, openapiPath, pathParameters, entryType, readOnly, ancestors);
			} else if (TaggedUnion.class.isAssignableFrom(rawType)) {
				walkTaggedUnionCases(boskPath, openapiPath, pathParameters, actualType, readOnly, ancestors);
			} else if (rawType.isRecord()) {
				if (ancestors.add(actualType)) {
					walkRecordComponents(boskPath, openapiPath, pathParameters, actualType, rawType, readOnly, ancestors);
					ancestors.remove(actualType);
				}
			}
		}

		private void walkRecordComponents(
			Path boskPath,
			String openapiPath,
			List<ObjectNode> pathParameters,
			Type recordType,
			Class<?> recordClass,
			boolean readOnly,
			Set<Type> ancestors
		) {
			for (RecordComponent component : recordClass.getRecordComponents()) {
				Type componentType = resolveTypeVariables(component.getGenericType(), recordType);
				Class<?> componentClass = rawClass(componentType);
				NodeKind childKind;
				if (StateTreeSerializer.isImplicitParameter(recordClass, component)) {
					childKind = NodeKind.IMPLICIT_REFERENCE;
				} else if (Phantom.class.isAssignableFrom(componentClass)) {
					childKind = NodeKind.PHANTOM;
				} else if (Optional.class.isAssignableFrom(componentClass)) {
					childKind = NodeKind.OPTIONAL_FIELD;
				} else {
					childKind = NodeKind.RECORD_FIELD;
				}
				walkPaths(
					boskPath.then(component.getName()),
					joinPath(openapiPath, component.getName()),
					pathParameters,
					childKind,
					readOnly,
					ancestors);
			}
		}

		private void walkContainerEntry(
			Path boskPath,
			String openapiPath,
			List<ObjectNode> pathParameters,
			Type identifierType,
			boolean readOnly,
			Set<Type> ancestors
		) {
			String parameterName = parameterName(identifierType, pathParameters);
			List<ObjectNode> entryParameters = new ArrayList<>(pathParameters);
			entryParameters.add(map(
				"name", parameterName,
				"in", "path",
				"required", true,
				"description", "A Bosk identifier for a " + nodeDescription(identifierType)
					+ ", URL-encoded as a single path segment",
				"schema", map("type", "string")));
			walkPaths(
				boskPath.then("-" + parameterName + "-"),
				joinPath(openapiPath, "{" + parameterName + "}"),
				entryParameters,
				NodeKind.CONTAINER_ENTRY,
				readOnly,
				ancestors);
		}

		private void walkTaggedUnionCases(
			Path boskPath,
			String openapiPath,
			List<ObjectNode> pathParameters,
			Type unionType,
			boolean readOnly,
			Set<Type> ancestors
		) {
			Type caseSupertype = parameterType(unionType, TaggedUnion.class, 0);
			MapValue<Type> caseMap;
			try {
				caseMap = StateTreeSerializer.getTaggedUnionCaseMap(rawClass(caseSupertype));
			} catch (InvalidTypeException e) {
				throw new IllegalStateException("Cannot determine tagged union cases for " + unionType, e);
			}
			for (String tag : caseMap.keySet()) {
				walkPaths(
					boskPath.then(tag),
					joinPath(openapiPath, tag),
					pathParameters,
					NodeKind.CASE,
					true,
					ancestors);
			}
		}

		private void addPath(
			String openapiPath,
			List<ObjectNode> pathParameters,
			Reference<?> reference,
			Type type,
			NodeKind kind,
			boolean readOnly
		) {
			ObjectNode pathItem = JsonNodeFactory.instance.objectNode();
			if (!pathParameters.isEmpty()) {
				pathItem.set("parameters", list(pathParameters.toArray()));
			}
			pathItem.set("get", getOperation(openapiPath, reference, type, kind, readOnly));
			if (!readOnly) {
				if (kind.puttable()) {
					pathItem.set("put", putOperation(openapiPath, reference, type, kind));
				}
				if (kind.deletable()) {
					pathItem.set("delete", deleteOperation(openapiPath, reference, kind));
				}
			}
			JsonNode previous = paths.putIfAbsent(openapiPath, pathItem);
			if (previous != null) {
				LOGGER.warn("Duplicate OpenAPI path \"{}\"; keeping the first definition", openapiPath);
			}
		}

		private ObjectNode getOperation(
			String openapiPath,
			Reference<?> reference,
			Type type,
			NodeKind kind,
			boolean readOnly
		) {
			if (kind == NodeKind.PHANTOM) {
				return map(
					"operationId", operationId("get", openapiPath),
					"tags", list("bosk-maintenance"),
					"summary", "Read phantom (always empty)",
					"description", "A Phantom is always empty, so this path always returns 404."
						+ " It is a placeholder that can be referenced but never holds a value.",
					"responses", map(
						"404", map("description", "A Phantom is always empty")));
			}

			boolean hasRevision = hasRevision(reference);
			ObjectNode success = map(
				"description", "The node's current JSON value",
				"content", map(APPLICATION_JSON_VALUE, map("schema", schemaFor(type))));
			if (hasRevision) {
				success.set("headers", map("ETag", etagHeader()));
			}
			String description = "Returns the JSON representation of the node at this path."
				+ " A node's value embeds its children, so the same data is also visible through the parent path.";
			if (kind == NodeKind.IMPLICIT_REFERENCE) {
				description += " This is an implicit reference computed by Bosk; it is not part of the stored JSON.";
			}
			if (readOnly) {
				description += " This path is read-only because it passes through a tagged-union case.";
			}
			ObjectNode responses = map(
				"200", success,
				"404", map("description", "The path is invalid, or no such node exists"));
			ObjectNode operation = map(
				"operationId", operationId("get", openapiPath),
				"tags", list("bosk-maintenance"),
				"summary", kind == NodeKind.IMPLICIT_REFERENCE
					? "Read implicit reference"
					: "Read " + nodeDescription(type),
				"description", description,
				"responses", responses);
			if (hasRevision) {
				ArrayNode parameters = JsonNodeFactory.instance.arrayNode();
				parameters.add(ifNoneMatchGetParameter());
				operation.set("parameters", parameters);
				responses.set("304", map(
					"description", "The node's revision still matches the If-None-Match tag, so the body is omitted",
					"headers", map("ETag", etagHeader())));
			}
			return operation;
		}

		private ObjectNode putOperation(
			String openapiPath,
			Reference<?> reference,
			Type type,
			NodeKind kind
		) {
			ArrayNode parameters = JsonNodeFactory.instance.arrayNode();
			boolean hasRevision = hasRevision(reference);
			if (hasRevision) {
				parameters.add(ifMatchParameter());
			}
			parameters.add(ifNoneMatchParameter());

			String description = "The request body is the node's new JSON value."
				+ " The write is submitted through `bosk.driver()` and returns `202 Accepted`;"
				+ " it fires hooks and any downstream replication or persistence."
				+ " A successful response does not mean the write has been applied.";
			if (hasRevision) {
				description += " Send the `ETag` from a previous `GET` in `If-Match` to apply the write only"
					+ " if the node still has that revision; a mismatch is a `202` no-op.";
			}
			description += " `If-None-Match: *` applies the write only if the node does not already exist.";
			if (kind == NodeKind.CONTAINER_ENTRY) {
				if (ListingEntry.class.isAssignableFrom(rawClass(type))) {
					description += " A listing entry is a unit value; `true` is a nonce that indicates the"
						+ " entry's presence, so the body must be `true`.";
				} else if (Entity.class.isAssignableFrom(rawClass(type))) {
					description += " The value's `id` must equal the path identifier.";
				}
			}

			return map(
				"operationId", operationId("put", openapiPath),
				"tags", list("bosk-maintenance"),
				"summary", "Replace " + nodeDescription(type),
				"description", description,
				"parameters", parameters,
				"requestBody", map(
					"required", true,
					"content", map(APPLICATION_JSON_VALUE, map("schema", schemaFor(type)))),
				"responses", map(
					"202", map("description", "The write was submitted through the Bosk driver"),
					"400", map("description", "The body, identifier, or precondition header is invalid"),
					"404", map("description", "The path is not a valid Bosk reference")));
		}

		private ObjectNode deleteOperation(
			String openapiPath,
			Reference<?> reference,
			NodeKind kind
		) {
			ArrayNode parameters = JsonNodeFactory.instance.arrayNode();
			boolean hasRevision = hasRevision(reference);
			if (hasRevision) {
				parameters.add(ifMatchParameter());
			}
			String description = switch (kind) {
				case OPTIONAL_FIELD -> "Removes the value, leaving the Optional field empty.";
				case PHANTOM -> "A Phantom is always empty, so deleting it has no effect.";
				case CONTAINER_ENTRY -> "Removes the entry from its container.";
				default -> "Removes the node.";
			};
			description += " The deletion is submitted through `bosk.driver()` and returns"
				+ " `202 Accepted`; it fires hooks and any downstream replication or persistence."
				+ " A successful response does not mean the deletion has been applied.";
			if (hasRevision) {
				description += " Send the `ETag` from a previous `GET` in `If-Match` to apply the deletion"
					+ " only if the node still has that revision; a mismatch is a `202` no-op.";
			}

			ObjectNode operation = map(
				"operationId", operationId("delete", openapiPath),
				"tags", list("bosk-maintenance"),
				"summary", "Delete " + nodeKindDescription(kind),
				"description", description,
				"responses", map(
					"202", map("description", "The deletion was submitted through the Bosk driver"),
					"400", map("description", "The precondition header is invalid"),
					"404", map("description", "The path is not a valid Bosk reference")));
			if (!parameters.isEmpty()) {
				operation.set("parameters", parameters);
			}
			return operation;
		}

		private ObjectNode schemaFor(Type type) {
			if (type instanceof Class<?> clazz) {
				return schemaForClass(clazz);
			} else if (type instanceof ParameterizedType parameterizedType) {
				return schemaForParameterizedType(parameterizedType);
			} else {
				LOGGER.warn("No OpenAPI schema for type \"{}\"; using an unconstrained schema", type.getTypeName());
				return map();
			}
		}

		private ObjectNode schemaForClass(Class<?> clazz) {
			if (clazz == Object.class) {
				return map();
			} else if (clazz.isEnum()) {
				ArrayNode constants = JsonNodeFactory.instance.arrayNode();
				for (Object constant : clazz.getEnumConstants()) {
					constants.add(((Enum<?>) constant).name());
				}
				return map("type", "string", "enum", constants);
			} else if (clazz == String.class) {
				return map("type", "string");
			} else if (clazz == char.class || clazz == Character.class) {
				return map("type", "string", "minLength", 1, "maxLength", 1);
			} else if (clazz == boolean.class || clazz == Boolean.class) {
				return map("type", "boolean");
			} else if (clazz == byte.class || clazz == Byte.class
				|| clazz == short.class || clazz == Short.class
				|| clazz == int.class || clazz == Integer.class) {
				return map("type", "integer", "format", "int32");
			} else if (clazz == long.class || clazz == Long.class) {
				return map("type", "integer", "format", "int64");
			} else if (clazz == BigInteger.class) {
				return map("type", "integer");
			} else if (clazz == float.class || clazz == Float.class) {
				return map("type", "number", "format", "float");
			} else if (clazz == double.class || clazz == Double.class) {
				return map("type", "number", "format", "double");
			} else if (clazz == BigDecimal.class) {
				return map("type", "number");
			} else if (Number.class.isAssignableFrom(clazz)) {
				return map("type", "number");
			} else if (Identifier.class.isAssignableFrom(clazz)) {
				return map(
					"type", "string",
					"description", "A Bosk identifier; it may not be empty or start or end with a hyphen",
					"minLength", 1);
			} else if (Reference.class.isAssignableFrom(clazz)) {
				return map("type", "string", "description", "A URL-encoded Bosk reference path");
			} else if (ListingEntry.class.isAssignableFrom(clazz)) {
				return map(
					"type", "boolean",
					"const", true,
					"description", "A listing entry is a unit value; `true` is a nonce that indicates"
						+ " the entry's presence. It carries no other information.");
			} else if (clazz.isRecord()) {
				return refFor(clazz);
			} else {
				LOGGER.warn("No OpenAPI schema for class \"{}\"; using an unconstrained schema", clazz.getName());
				return map();
			}
		}

		private ObjectNode schemaForParameterizedType(ParameterizedType type) {
			Class<?> rawType = rawClass(type);
			if (Optional.class.isAssignableFrom(rawType)) {
				return schemaFor(type.getActualTypeArguments()[0]);
			} else if (Catalog.class.isAssignableFrom(rawType)) {
				Type entryType = parameterType(type, Catalog.class, 0);
				ObjectNode entrySchema = map(
					"type", "object",
					"minProperties", 1,
					"maxProperties", 1,
					"propertyNames", map(
						"type", "string",
						"description", "A Bosk identifier"),
					"additionalProperties", schemaFor(entryType));
				ObjectNode result = map("type", "array", "items", entrySchema);
				result.put("description", "A Catalog is an array of single-entry objects,"
					+ " each mapping an Identifier to an Entity.");
				return result;
			} else if (SideTable.class.isAssignableFrom(rawType)) {
				Type valueType = parameterType(type, SideTable.class, 1);
				ObjectNode entrySchema = map(
					"type", "object",
					"minProperties", 1,
					"maxProperties", 1,
					"propertyNames", map(
						"type", "string",
						"description", "A Bosk identifier"),
					"additionalProperties", schemaFor(valueType));
				return map(
					"type", "object",
					"properties", map(
						"valuesById", map("type", "array", "items", entrySchema),
						"domain", map(
							"type", "string",
							"description", "A URL-encoded Bosk reference path to the domain Catalog")),
					"required", list("valuesById", "domain"));
			} else if (Listing.class.isAssignableFrom(rawType)) {
				return map(
					"type", "object",
					"properties", map(
						"ids", map("type", "array", "items", map("type", "string")),
						"domain", map(
							"type", "string",
							"description", "A URL-encoded Bosk reference path to the domain Catalog")),
					"required", list("ids", "domain"));
			} else if (TaggedUnion.class.isAssignableFrom(rawType)) {
				return taggedUnionSchema(type);
			} else if (ListValue.class.isAssignableFrom(rawType)) {
				return map("type", "array", "items", schemaFor(type.getActualTypeArguments()[0]));
			} else if (MapValue.class.isAssignableFrom(rawType)) {
				return map("type", "object", "additionalProperties", schemaFor(type.getActualTypeArguments()[0]));
			} else if (Reference.class.isAssignableFrom(rawType)) {
				return map("type", "string", "description", "A URL-encoded Bosk reference path");
			} else if (rawType.isRecord()) {
				return refFor(type);
			} else {
				LOGGER.warn("No OpenAPI schema for type \"{}\"; using an unconstrained schema", type.getTypeName());
				return map();
			}
		}

		private ObjectNode taggedUnionSchema(ParameterizedType type) {
			Type caseSupertype = parameterType(type, TaggedUnion.class, 0);
			MapValue<Type> caseMap;
			try {
				caseMap = StateTreeSerializer.getTaggedUnionCaseMap(rawClass(caseSupertype));
			} catch (InvalidTypeException e) {
				throw new IllegalStateException("Cannot determine tagged union cases for " + type, e);
			}
			ArrayNode cases = JsonNodeFactory.instance.arrayNode();
			for (Map.Entry<String, Type> entry : caseMap.entrySet()) {
				Type caseType = resolveTypeVariables(entry.getValue(), caseSupertype);
				cases.add(map(
					"type", "object",
					"properties", map(entry.getKey(), schemaFor(caseType)),
					"required", list(entry.getKey()),
					"additionalProperties", false));
			}
			if (cases.isEmpty()) {
				LOGGER.warn("Tagged union \"{}\" has no cases; using an unconstrained schema", type.getTypeName());
				return map();
			}
			return map(
				"oneOf", cases,
				"description", "A tagged union with exactly one case property present");
		}

		private ObjectNode refFor(Type recordType) {
			String name = schemaName(recordType);
			if (queuedSchemaTypes.add(recordType)) {
				schemaQueue.add(recordType);
			}
			return map("$ref", "#/components/schemas/" + name);
		}

		private String schemaName(Type type) {
			return schemaNames.computeIfAbsent(type, this::registerSchemaName);
		}

		private String registerSchemaName(Type type) {
			String simpleName = sanitize(simpleTypeName(type));
			Type existing = typesBySchemaName.get(simpleName);
			if (existing == null || existing.equals(type)) {
				typesBySchemaName.put(simpleName, type);
				return simpleName;
			}

			String qualifiedName = sanitize(qualifiedTypeName(type));
			String candidate = qualifiedName;
			for (int suffix = 2; typesBySchemaName.containsKey(candidate); suffix++) {
				candidate = qualifiedName + "_" + suffix;
			}
			typesBySchemaName.put(candidate, type);
			return candidate;
		}

		private static String simpleTypeName(Type type) {
			if (type instanceof Class<?> clazz) {
				String simpleName = clazz.getSimpleName();
				return simpleName.isEmpty() ? clazz.getName() : simpleName;
			} else if (type instanceof ParameterizedType parameterizedType) {
				StringBuilder result = new StringBuilder(simpleTypeName(parameterizedType.getRawType()));
				for (Type argument : parameterizedType.getActualTypeArguments()) {
					result.append('_').append(simpleTypeName(argument));
				}
				return result.toString();
			} else {
				return type.getTypeName();
			}
		}

		private static String qualifiedTypeName(Type type) {
			if (type instanceof Class<?> clazz) {
				return clazz.getName();
			} else if (type instanceof ParameterizedType parameterizedType) {
				StringBuilder result = new StringBuilder(qualifiedTypeName(parameterizedType.getRawType()));
				for (Type argument : parameterizedType.getActualTypeArguments()) {
					result.append('_').append(qualifiedTypeName(argument));
				}
				return result.toString();
			} else {
				return type.getTypeName();
			}
		}

		private static String sanitize(String name) {
			String result = name.replace('$', '.').replaceAll("[^A-Za-z0-9._-]", "_");
			return result.isEmpty() ? "Schema" : result;
		}

		private ObjectNode buildRecordSchema(Type recordType) {
			Class<?> recordClass = rawClass(recordType);
			ObjectNode properties = JsonNodeFactory.instance.objectNode();
			ArrayNode required = JsonNodeFactory.instance.arrayNode();
			Set<String> polyfilledFields = polyfilledFields(recordClass);
			for (RecordComponent component : recordClass.getRecordComponents()) {
				Type componentType = resolveTypeVariables(component.getGenericType(), recordType);
				Class<?> componentClass = rawClass(componentType);
				if (StateTreeSerializer.isImplicitParameter(recordClass, component)
					|| Phantom.class.isAssignableFrom(componentClass)) {
					continue;
				}
				boolean optional = Optional.class.isAssignableFrom(componentClass);
				Type valueType = optional ? parameterType(componentType, Optional.class, 0) : componentType;
				properties.set(component.getName(), schemaFor(valueType));
				if (!optional && !polyfilledFields.contains(component.getName())) {
					required.add(component.getName());
				}
			}
			ObjectNode result = map(
				"type", "object",
				"title", simpleTypeName(recordType));
			if (!properties.isEmpty()) {
				result.set("properties", properties);
			}
			if (!required.isEmpty()) {
				result.set("required", required);
			}
			return result;
		}

		private static Set<String> polyfilledFields(Class<?> recordClass) {
			Set<String> result = new HashSet<>();
			for (Field field : recordClass.getDeclaredFields()) {
				for (Polyfill polyfill : field.getAnnotationsByType(Polyfill.class)) {
					result.addAll(Arrays.asList(polyfill.value()));
				}
			}
			return result;
		}

		private static boolean hasRevision(Reference<?> reference) {
			return reference.thenIfValid(Identifier.class, "revision").isPresent();
		}

		private String operationId(String method, String openapiPath) {
			String relativePath = openapiPath.substring(basePath.length());
			StringBuilder result = new StringBuilder(method);
			boolean anySegment = false;
			for (String segment : relativePath.split("/")) {
				if (segment.isEmpty()) {
					continue;
				}
				anySegment = true;
				if (segment.startsWith("{") && segment.endsWith("}")) {
					result.append("By").append(capitalize(segment.substring(1, segment.length() - 1)));
				} else {
					result.append(capitalize(segment));
				}
			}
			if (!anySegment) {
				result.append("Root");
			}
			String candidate = result.toString();
			for (int suffix = 2; !operationIds.add(candidate); suffix++) {
				candidate = result + "_" + suffix;
			}
			return candidate;
		}

		private static String capitalize(String value) {
			if (value.isEmpty()) {
				return value;
			} else {
				return Character.toUpperCase(value.charAt(0)) + value.substring(1);
			}
		}

		private static String parameterName(Type type, List<ObjectNode> existingParameters) {
			String simpleName = rawClass(type).getSimpleName();
			String base = simpleName.isEmpty()
				? "id"
				: Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1) + "Id";
			base = base.replaceAll("[^A-Za-z0-9_]", "_");
			if (base.isEmpty() || Character.isDigit(base.charAt(0))) {
				base = "id" + base;
			}
			Set<String> existingNames = new HashSet<>();
			for (ObjectNode parameter : existingParameters) {
				existingNames.add(parameter.get("name").asString());
			}
			String candidate = base;
			for (int suffix = 2; existingNames.contains(candidate); suffix++) {
				candidate = base + "_" + suffix;
			}
			return candidate;
		}

		private static String joinPath(String base, String segment) {
			return base.endsWith("/") ? base + segment : base + "/" + segment;
		}

		private static String nodeDescription(Type type) {
			if (ListingEntry.class.isAssignableFrom(rawClass(type))) {
				return "listing entry";
			} else {
				String simpleName = rawClass(type).getSimpleName();
				return simpleName.isEmpty() ? "node" : simpleName;
			}
		}

		private static String nodeKindDescription(NodeKind kind) {
			return switch (kind) {
				case OPTIONAL_FIELD -> "Optional value";
				case CONTAINER_ENTRY -> "container entry";
				case PHANTOM -> "phantom";
				default -> "node";
			};
		}

		private static ObjectNode ifMatchParameter() {
			return map(
				"name", "If-Match",
				"in", "header",
				"required", false,
				"description", "The ETag from a previous GET of this node, sent verbatim."
					+ " A mismatch is a 202 no-op.",
				"schema", map("type", "string"));
		}

		private static ObjectNode ifNoneMatchParameter() {
			return map(
				"name", "If-None-Match",
				"in", "header",
				"required", false,
				"description", "Use `*` to apply the write only if the node does not already exist.",
				"schema", map("type", "string", "enum", list("*")));
		}

		private static ObjectNode ifNoneMatchGetParameter() {
			return map(
				"name", "If-None-Match",
				"in", "header",
				"required", false,
				"description", "The ETag from a previous GET of this node, sent verbatim, or `*`."
					+ " If it matches the node's current revision, the response is `304` with no body."
					+ " `*` lists every possible ETag, so it matches any node with a `revision` field.",
				"schema", map("type", "string"));
		}

		private static ObjectNode etagHeader() {
			return map(
				"description", "A strong ETag derived from the node's revision field."
					+ " Send it back verbatim in an If-Match header to condition a write,"
					+ " or in an If-None-Match header to get a bodyless 304 while the revision is unchanged.",
				"schema", map("type", "string"));
		}

		private static String version() {
			Package boskPackage = Bosk.class.getPackage();
			String version = boskPackage == null ? null : boskPackage.getImplementationVersion();
			return version == null ? "1.0.0" : version;
		}
	}


	private static ObjectNode map(Object... keyValues) {
		if (keyValues.length % 2 != 0) {
			throw new IllegalArgumentException("Expected an even number of key-value arguments");
		}
		ObjectNode result = JsonNodeFactory.instance.objectNode();
		for (int i = 0; i < keyValues.length; i += 2) {
			String key = (String) keyValues[i];
			Object value = keyValues[i + 1];
			JsonNode node = toNode(value);
			if (node == null) {
				result.putNull(key);
			} else {
				result.set(key, node);
			}
		}
		return result;
	}

	private static ArrayNode list(Object... items) {
		ArrayNode result = JsonNodeFactory.instance.arrayNode();
		for (Object item : items) {
			JsonNode node = toNode(item);
			if (node == null) {
				result.addNull();
			} else {
				result.add(node);
			}
		}
		return result;
	}

	private static JsonNode toNode(Object value) {
		if (value instanceof JsonNode node) {
			return node;
		} else if (value instanceof String string) {
			return JsonNodeFactory.instance.stringNode(string);
		} else if (value instanceof Integer integer) {
			return JsonNodeFactory.instance.numberNode(integer.intValue());
		} else if (value instanceof Long longValue) {
			return JsonNodeFactory.instance.numberNode(longValue.longValue());
		} else if (value instanceof Boolean booleanValue) {
			return JsonNodeFactory.instance.booleanNode(booleanValue.booleanValue());
		} else if (value == null) {
			return null;
		} else {
			throw new IllegalArgumentException("Unsupported OpenAPI value: " + value.getClass().getName());
		}
	}

	private static final String DESCRIPTION_TEMPLATE = """
		Direct JSON access to the state tree of the Bosk named "{name}". Every node in the tree is
		addressable, and a node's value embeds its children, so the same data may appear at more
		than one path. Writes are submitted through the Bosk driver and return 202 Accepted; they
		fire hooks and any downstream replication or persistence. Conditional writes use the ETag
		from a previous GET in an If-Match header; a mismatch is a 202 no-op. Authentication is
		application-defined and must cover the maintenance path.
		""";

	private static final Logger LOGGER = LoggerFactory.getLogger(BoskOpenApi.class);
}
