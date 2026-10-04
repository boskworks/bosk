package works.bosk.graphql;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.PropertyDataFetcher;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.ListValue;
import works.bosk.Listing;
import works.bosk.MapValue;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.RootReference;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.StateTreeSerializer;
import works.bosk.TaggedUnion;
import works.bosk.TaggedUnionCase;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.MalformedPathException;
import works.bosk.exceptions.NonexistentReferenceException;
import works.bosk.graphql.exceptions.UnsupportedNameException;
import works.bosk.graphql.exceptions.UnsupportedTypeException;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLFloat;
import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLString;
import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLCodeRegistry.newCodeRegistry;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static java.util.Collections.unmodifiableMap;
import static works.bosk.ReferenceUtils.parameterType;

/**
 * Generates a {@link GraphQLSchema} from a Bosk state tree.
 * The schema is read-only, providing only a {@code Query} type.
 * <p>
 * Use {@link #schemaFor(Bosk)} to produce a schema, which can be passed to
 * {@code graphql.GraphQL.newGraphQL(schema).build()} for execution.
 * <p>
 * All record-typed fields emit {@code GraphQLTypeReference} for lazy resolution,
 * supporting recursive and mutually-referencing types.
 * <p>
 * Type names are derived from the Java class {@link Class#getSimpleName() simple name}.
 * Names starting with {@code _} or containing {@code $} throw {@link UnsupportedNameException};
 * names containing internal {@code _} characters are automatically mangled to avoid
 * ambiguity with the generated wrapper type names (e.g. {@code _CatalogEntry_Foo}).
 * <p>
 * See the {@code example-hello} project for a working Spring Boot endpoint.
 *
 * @see <a href="https://javadoc.io/doc/works.bosk/bosk-graphql/latest/works.bosk.graphql/module-summary.html">bosk-graphql javadocs</a>
 */
public class BoskGraphQL {
	public static GraphQLSchema schemaFor(Bosk<?> bosk) {
		return new Generator(bosk.rootReference()).generate();
	}

	/**
	 * A single schema generation operation is a stateful breadth-first walk over
	 * a number of datatypes. That state is stored here.
	 */
	private static class Generator {
		private final RootReference<? extends StateTreeNode> rootReference;

		Generator(RootReference<? extends StateTreeNode> rootReference) {
			this.rootReference = rootReference;
		}

		/**
		 * Registry of user-provided type names, used to detect duplicates.
		 * A name is registered here before its type is built and added to {@link #builtTypesByName}.
		 */
		private final Map<String, Class<?>> typesByName = new HashMap<>();

		/**
		 * The breadth-first traversal queue.
		 * Only records go in here; all other types can be fully processed when they
		 * are encountered because they can't be responsible for recursive type
		 * cycles without involving a record.
		 */
		private final ArrayDeque<Class<? extends Record>> queue = new ArrayDeque<>();

		/**
		 * All built GraphQL types, keyed by name, including generated wrapper types
		 * (e.g. entry types for {@link works.bosk.Catalog}, {@link works.bosk.SideTable}, etc.).
		 * A user-provided type is only added here after its name is registered in {@link #typesByName}.
		 */
		private final Map<String, GraphQLNamedOutputType> builtTypesByName = new LinkedHashMap<>();

		private GraphQLSchema generate() {
			var reg = newCodeRegistry();

			ensureQueued(rootReference.targetClass());
			while (!queue.isEmpty()) {
				processRecord(queue.removeFirst(), reg);
			}

			var queryBuilder = GraphQLObjectType.newObject().name("Query");
			for (var c : rootReference.targetClass().getRecordComponents()) {
				addRecordComponent(queryBuilder, c, "Query", reg);
			}
			var queryType = queryBuilder.build();

			return GraphQLSchema.newSchema()
				.query(queryType)
				.codeRegistry(reg.build())
				.additionalTypes(new LinkedHashSet<>(builtTypesByName.values()))
				.build();
		}

		private void processRecord(Class<? extends Record> clazz, GraphQLCodeRegistry.Builder reg) {
			String name = typeName(clazz);
			var builder = GraphQLObjectType.newObject().name(name);
			for (var c : clazz.getRecordComponents()) {
				addRecordComponent(builder, c, name, reg);
			}
			builtTypesByName.put(name, builder.build());
		}

		private void addRecordComponent(
			GraphQLObjectType.Builder parentBuilder,
			RecordComponent component,
			String parentTypeName,
			GraphQLCodeRegistry.Builder reg
		) {
			String fieldName = component.getName();
			validateFieldName(fieldName, parentTypeName);
			Type genericType = component.getGenericType();

			if (genericType instanceof ParameterizedType pt) {
				var rawType = (Class<?>) pt.getRawType();
				var typeArgs = pt.getActualTypeArguments();

				if (Optional.class.isAssignableFrom(rawType)) {
					addOptionalField(parentBuilder, fieldName, typeArgs[0], parentTypeName, reg);
				} else if (Catalog.class.isAssignableFrom(rawType)) {
					addCatalogField(parentBuilder, fieldName, typeArgs[0], parentTypeName, reg);
				} else if (SideTable.class.isAssignableFrom(rawType)) {
					addSideTableField(parentBuilder, fieldName, typeArgs[1], parentTypeName, reg);
				} else if (Listing.class.isAssignableFrom(rawType)) {
					addListingField(parentBuilder, fieldName, typeArgs[0], parentTypeName, reg);
				} else if (TaggedUnion.class.isAssignableFrom(rawType)) {
					addTaggedUnionField(parentBuilder, fieldName, typeArgs[0], parentTypeName, reg);
				} else if (ListValue.class.isAssignableFrom(rawType)) {
					addListValueField(parentBuilder, fieldName, typeArgs[0]);
				} else if (MapValue.class.isAssignableFrom(rawType)) {
					addMapValueField(parentBuilder, fieldName, typeArgs[0], parentTypeName, reg);
				} else if (Reference.class.isAssignableFrom(rawType)) {
					addReferenceField(parentBuilder, fieldName, typeArgs[0], parentTypeName, reg);
				} else {
					throw new UnsupportedTypeException("Unsupported parameterized type " + rawType.getName()
						+ " for field " + fieldName + " on " + parentTypeName);
				}
			} else if (genericType instanceof Class<?> clazz) {
				GraphQLOutputType fieldType;
				if (Identifier.class.isAssignableFrom(clazz)) {
					fieldType = nonNull(GRAPHQL_IDENTIFIER);
				} else if (clazz.isEnum()) {
					fieldType = nonNull(buildEnumType(clazz));
				} else if (clazz.isRecord()) {
					ensureQueued(clazz);
					fieldType = nonNull(typeRef(typeName(clazz)));
				} else if (ListValue.class.isAssignableFrom(clazz)) {
					addListValueField(parentBuilder, fieldName, parameterType(clazz, ListValue.class, 0));
					return;
				} else {
					fieldType = nonNull(resolveType(clazz));
				}
				parentBuilder.field(newFieldDefinition()
					.name(fieldName)
					.type(fieldType)
					.build());
				// The default PropertyDataFetcher works for these
			} else {
				throw new UnsupportedTypeException("Unsupported generic type " + genericType.getTypeName()
					+ " for field " + fieldName + " on " + parentTypeName);
			}
		}

		private void addOptionalField(
			GraphQLObjectType.Builder parentBuilder,
			String fieldName,
			Type innerType,
			String parentTypeName,
			GraphQLCodeRegistry.Builder reg
		) {
			if (innerType instanceof ParameterizedType pt) {
				var rawType = (Class<?>) pt.getRawType();
				if (Reference.class.isAssignableFrom(rawType)) {
					addOptionalReferenceField(parentBuilder, fieldName, pt, parentTypeName, reg);
					return;
				}
				if (MapValue.class.isAssignableFrom(rawType)
					|| Catalog.class.isAssignableFrom(rawType)
					|| SideTable.class.isAssignableFrom(rawType)
					|| Listing.class.isAssignableFrom(rawType)
					|| TaggedUnion.class.isAssignableFrom(rawType)) {
					throw new UnsupportedTypeException("Optional<" + rawType.getSimpleName() + "<...>> fields are not yet supported"
						+ " (field " + fieldName + " on " + parentTypeName + ")");
				}
			}
			parentBuilder.field(newFieldDefinition()
				.name(fieldName)
				.type(resolveType(innerType))
				.build());
			var fetcher = PropertyDataFetcher.fetching(fieldName);
			reg.dataFetcher(coordinates(parentTypeName, fieldName), (DataFetcher<?>) env -> {
				Optional<?> opt = uncheckedCast(fetcher.get(env));
				return opt == null ? null : opt.orElse(null);
			});
		}

		private void addOptionalReferenceField(
			GraphQLObjectType.Builder parentBuilder,
			String fieldName,
			ParameterizedType referenceType,
			String parentTypeName,
			GraphQLCodeRegistry.Builder reg
		) {
			Type targetType = referenceType.getActualTypeArguments()[0];
			String entryName = "_Reference_" + typeName(targetType);
			buildEntryType(entryName, GRAPHQL_PATH, PATH_FIELD, targetType, false);
			parentBuilder.field(newFieldDefinition()
				.name(fieldName)
				.type(typeRef(entryName))
				.build());
			var fetcher = PropertyDataFetcher.fetching(fieldName);
			reg.dataFetcher(coordinates(parentTypeName, fieldName), (DataFetcher<?>) env -> {
				Optional<?> opt = uncheckedCast(fetcher.get(env));
				if (opt == null || opt.isEmpty()) {
					return null;
				}
				Reference<?> ref = uncheckedCast(opt.get());
				return hashMapOf(PATH_FIELD, ref.path(), VALUE_FIELD, ref.valueIfExists());
			});
		}

		private void addListValueField(
			GraphQLObjectType.Builder parentBuilder,
			String fieldName,
			Type elementType
		) {
			parentBuilder.field(newFieldDefinition()
				.name(fieldName)
				.type(nonNull(GraphQLList.list(nonNull(resolveType(elementType)))))
				.build());
			// The default PropertyDataFetcher returns ListValue, which implements List,
			// so GraphQLList can handle it natively without our help.
		}

		private void addMapValueField(
			GraphQLObjectType.Builder parentBuilder,
			String fieldName,
			Type elementType,
			String parentTypeName,
			GraphQLCodeRegistry.Builder reg
		) {
			String entryName = "_MapValueEntry_" + typeName(elementType);
			buildEntryType(entryName, GraphQLString, KEY_FIELD, elementType, true);
			parentBuilder.field(newFieldDefinition()
				.name(fieldName)
				.argument(GraphQLArgument.newArgument()
					.name(KEY_FIELD)
					.type(GraphQLString)
					.build())
				.type(nonNull(GraphQLList.list(nonNull(typeRef(entryName)))))
				.build());
			var fetcher = PropertyDataFetcher.fetching(fieldName);
			// No key filter; return all
			// No match
			reg.dataFetcher(coordinates(parentTypeName, fieldName), (DataFetcher<?>) env -> {
				MapValue<Object> mapValue = uncheckedCast(fetcher.get(env));
				String keyArg = env.getArgument(KEY_FIELD);
				if (keyArg == null) {
					// No key filter; return all
					List<Map<String, Object>> result = new ArrayList<>();
					for (var entry : mapValue.entrySet()) {
						result.add(Map.of(KEY_FIELD, entry.getKey(), VALUE_FIELD, entry.getValue()));
					}
					return result;
				} else {
					Object value = mapValue.get(keyArg);
					if (value == null) {
						// No match
						return List.of();
					} else {
						return List.of(Map.of(KEY_FIELD, keyArg, VALUE_FIELD, value));
					}
				}
			});
		}

		private <E extends Entity> void addCatalogField(
			GraphQLObjectType.Builder parentBuilder,
			String fieldName,
			Type elementType,
			String parentTypeName,
			GraphQLCodeRegistry.Builder reg
		) {
			String elemName = typeName(elementType);
			ensureQueued(elementType);
			parentBuilder.field(newFieldDefinition()
				.name(fieldName)
				.argument(GraphQLArgument.newArgument()
					.name(ID_FIELD)
					.type(GRAPHQL_IDENTIFIER)
					.build())
				.type(nonNull(GraphQLList.list(nonNull(typeRef(elemName)))))
				.build());
			var fetcher = PropertyDataFetcher.fetching(fieldName);
			// No id filter; return all
			// no match
			reg.dataFetcher(coordinates(parentTypeName, fieldName), (DataFetcher<?>) env -> {
				Catalog<E> catalog = uncheckedCast(fetcher.get(env));
				Identifier id = env.getArgument(ID_FIELD);
				if (id == null) {
					// No id filter; return all
					return catalog.stream().toList();
				} else {
					E entry = catalog.get(id);
					return entry == null
						? List.of() // no match
						: List.of(entry);
				}
			});
		}

		private <K extends Entity, V> void addSideTableField(
			GraphQLObjectType.Builder parentBuilder,
			String fieldName,
			Type valueType,
			String parentTypeName,
			GraphQLCodeRegistry.Builder reg
		) {
			addContainerEntryField(parentBuilder, fieldName, valueType, "SideTable", true);
			var fetcher = PropertyDataFetcher.fetching(fieldName);
			// No id filter; return all
			// no match
			reg.dataFetcher(coordinates(parentTypeName, fieldName), (DataFetcher<?>) env -> {
				SideTable<K, V> table = uncheckedCast(fetcher.get(env));
				Identifier idArg = env.getArgument(ID_FIELD);
				if (idArg == null) {
					// No id filter; return all
					List<Map<String, Object>> result = new ArrayList<>();
					table.forEachID((id, value) -> result.add(Map.of(
						PATH_FIELD, table.domain().then(id).path(),
						VALUE_FIELD, value)));
					return result;
				} else {
					if (table.hasID(idArg)) {
						return List.of(Map.of(
							PATH_FIELD, table.domain().then(idArg).path(),
							VALUE_FIELD, table.get(idArg)
						));
					} else {
						return List.of(); // no match
					}
				}
			});
		}

		/**
		 * Our handling of Listings in GraphQL differs from their handling in most of bosk.
		 * They are treated in a special and unusual way.
		 * <p>
		 * Most of bosk treats a listing like a {@code SideTable} whose value type
		 * is the unit type, but for GraphQL it's more appropriate to "dereference" the
		 * entries and include fields of the referenced entities in the query results.
		 * If there's no such entity, the value is represented by a null.
		 */
		private <E extends Entity> void addListingField(
			GraphQLObjectType.Builder parentBuilder,
			String fieldName,
			Type elementType,
			String parentTypeName,
			GraphQLCodeRegistry.Builder reg
		) {
			addContainerEntryField(parentBuilder, fieldName, elementType, "Listing", false);
			var fetcher = PropertyDataFetcher.fetching(fieldName);
			// No id filter; return all
			// no match
			reg.dataFetcher(coordinates(parentTypeName, fieldName), (DataFetcher<?>) env -> {
				Listing<E> listing = uncheckedCast(fetcher.get(env));
				Identifier idArg = env.getArgument(ID_FIELD);
				if (idArg == null) {
					// No id filter; return all
					List<Map<String, Object>> result = new ArrayList<>();
					var domain = listing.domain();
					for (Identifier id : listing.ids()) {
						result.add(hashMapOf(
							PATH_FIELD, domain.then(id).path(),
							VALUE_FIELD, safeGetValue(listing, id)
						));
					}
					return result;
				} else {
					if (listing.containsID(idArg)) {
						return List.of(hashMapOf(
							PATH_FIELD, listing.domain().then(idArg).path(),
							VALUE_FIELD, safeGetValue(listing, idArg)
						));
					} else {
						return List.of(); // no match
					}
				}
			});
		}

		private void addContainerEntryField(
			GraphQLObjectType.Builder parentBuilder,
			String fieldName,
			Type valueType,
			String containerKind,
			boolean valueNonNull
		) {
			String entryName = "_" + containerKind + "Entry_" + typeName(valueType);
			buildEntryType(entryName, GRAPHQL_PATH, PATH_FIELD, valueType, valueNonNull);
			parentBuilder.field(newFieldDefinition()
				.name(fieldName)
				.argument(GraphQLArgument.newArgument()
					.name(ID_FIELD) // Query by ID rather than path
					.type(GRAPHQL_IDENTIFIER)
					.build())
				.type(nonNull(GraphQLList.list(nonNull(typeRef(entryName)))))
				.build());
		}

		private void addReferenceField(
			GraphQLObjectType.Builder parentBuilder,
			String fieldName,
			Type targetType,
			String parentTypeName,
			GraphQLCodeRegistry.Builder reg
		) {
			String entryName = "_Reference_" + typeName(targetType);
			buildEntryType(entryName, GRAPHQL_PATH, PATH_FIELD, targetType, false);
			parentBuilder.field(newFieldDefinition()
				.name(fieldName)
				.type(nonNull(typeRef(entryName)))
				.build());
			var fetcher = PropertyDataFetcher.fetching(fieldName);
			reg.dataFetcher(coordinates(parentTypeName, fieldName), (DataFetcher<?>) env -> {
				Reference<?> ref = uncheckedCast(fetcher.get(env));
				return hashMapOf(
					PATH_FIELD, ref.path(),
					VALUE_FIELD, ref.valueIfExists()
				);
			});
		}

		@SuppressWarnings("unchecked")
		private <V extends TaggedUnionCase> void addTaggedUnionField(
			GraphQLObjectType.Builder parentBuilder,
			String fieldName,
			Type caseSupertype,
			String parentTypeName,
			GraphQLCodeRegistry.Builder reg
		) {
			// GraphQL builds schemas from raw classes, so a parameterized
			// case supertype isn't supported here.
			Class<V> caseSupertypeClass = (Class<V>) caseSupertype;
			String ifaceName = typeName(caseSupertypeClass);

			// Kind of like a computeIfAbsent, but we can produce more than one
			// table entry on each call, so we can't actually use computeIfAbsent.
			if (!builtTypesByName.containsKey(ifaceName)) {
				registerTypeName(ifaceName, caseSupertypeClass);

				MapValue<Type> caseMap;
				try {
					caseMap = StateTreeSerializer.getTaggedUnionCaseMap(caseSupertypeClass);
				} catch (InvalidTypeException e) {
					throw new IllegalStateException("Cannot build schema for " + ifaceName, e);
				}

				var interfaceBuilder = GraphQLInterfaceType.newInterface().name(ifaceName);
				interfaceBuilder.field(newFieldDefinition()
					.name(TAG_FIELD)
					.type(nonNull(GraphQLString))
					.build());
				var interfaceType = interfaceBuilder.build();
				builtTypesByName.put(ifaceName, interfaceType);

				for (var entry : caseMap.entrySet()) {
					Class<?> caseClass = (Class<?>) entry.getValue();
					String typeName = typeName(caseClass);
					registerTypeName(typeName, caseClass);
					var objBuilder = GraphQLObjectType.newObject()
						.name(typeName)
						.withInterface(interfaceType);
					objBuilder.field(newFieldDefinition()
						.name(TAG_FIELD)
						.type(nonNull(GraphQLString))
						.build());
					emitRecordComponents(objBuilder, caseClass, reg);
					builtTypesByName.put(typeName, objBuilder.build());
				}

				reg.typeResolver((GraphQLInterfaceType) builtTypesByName.get(ifaceName), env -> {
					Object value = env.getObject();
					GraphQLObjectType result = (GraphQLObjectType) builtTypesByName.get(typeName(value.getClass()));
					if (result == null) {
						throw new IllegalStateException("Unknown tagged union case type: " + value.getClass().getName());
					}
					return result;
				});
			}

			parentBuilder.field(newFieldDefinition()
				.name(fieldName)
				.type(nonNull(typeRef(ifaceName)))
				.build());
			var fetcher = PropertyDataFetcher.fetching(fieldName);
			reg.dataFetcher(coordinates(parentTypeName, fieldName), (DataFetcher<?>) env1 -> {
				TaggedUnion<V> union = uncheckedCast(fetcher.get(env1));
				return union.value();
			});
		}

		private void emitRecordComponents(
			GraphQLObjectType.Builder builder,
			Class<?> recordClass,
			GraphQLCodeRegistry.Builder reg
		) {
			for (RecordComponent component : recordClass.getRecordComponents()) {
				addRecordComponent(builder, component, typeName(recordClass), reg);
			}
		}

		/**
		 * @param type a record type
		 */
		private void ensureQueued(Type type) {
			if (type instanceof Class<?> clazz && clazz.isRecord()) {
				String name = typeName(clazz);
				if (!builtTypesByName.containsKey(name) && registerTypeName(name, clazz)) {
					@SuppressWarnings("unchecked")
					Class<? extends Record> recordClass = (Class<? extends Record>) clazz;
					queue.add(recordClass);
				}
			} else {
				throw new UnsupportedTypeException("Expected a record type: " + type);
			}
		}

		private GraphQLOutputType resolveType(Type type) {
			if (type instanceof Class<?> clazz) {
				GraphQLOutputType seedType = SEED_TYPES.get(clazz);
				if (seedType != null) {
					return seedType;
				}
				if (clazz.isEnum()) {
					GraphQLNamedOutputType existing = builtTypesByName.get(typeName(clazz));
					if (existing instanceof GraphQLEnumType enumType) {
						return enumType;
					} else {
						return buildEnumType(clazz);
					}
				}
				if (clazz.isRecord()) {
					ensureQueued(clazz);
					return typeRef(typeName(clazz));
				}
				throw new UnsupportedTypeException("Unsupported type: " + clazz.getName());
			}
			if (type instanceof ParameterizedType pt) {
				var rawType = (Class<?>) pt.getRawType();
				if (Optional.class.isAssignableFrom(rawType)) {
					return resolveType(pt.getActualTypeArguments()[0]);
				}
				if (ListValue.class.isAssignableFrom(rawType)) {
					return GraphQLList.list(nonNull(resolveType(pt.getActualTypeArguments()[0])));
				}
				throw new UnsupportedTypeException("Unsupported parameterized type " + rawType.getName()
					+ " for field type. Consider wrapping in a record class.");
			}
			throw new UnsupportedTypeException("Unsupported generic type: " + type.getTypeName());
		}

		private void buildEntryType(String name, GraphQLScalarType keyType, String keyFieldName, Type valueType, boolean valueNonNull) {
			if (builtTypesByName.containsKey(name)) {
				return;
			}
			var builder = GraphQLObjectType.newObject().name(name);
			builder.field(newFieldDefinition()
				.name(keyFieldName)
				.type(nonNull(keyType))
				.build());
			var valueGraphQL = resolveType(valueType);
			builder.field(newFieldDefinition()
				.name(VALUE_FIELD)
				.type(valueNonNull ? nonNull(valueGraphQL) : valueGraphQL)
				.build());
			builtTypesByName.put(name, builder.build());
		}

		private GraphQLEnumType buildEnumType(Class<?> enumClass) {
			String name = typeName(enumClass);
			if (!registerTypeName(name, enumClass)) {
				return (GraphQLEnumType) builtTypesByName.get(name);
			}
			var builder = GraphQLEnumType.newEnum().name(name);
			for (var constant : enumClass.getEnumConstants()) {
				var e = (Enum<?>) constant;
				builder.value(e.name());
			}
			var type = builder.build();
			builtTypesByName.put(name, type);
			return type;
		}

		private void validateFieldName(String fieldName, String parentTypeName) {
			if (fieldName.indexOf('$') >= 0) {
				throw new UnsupportedNameException("GraphQL field name cannot contain '$': " + fieldName
					+ " (on type " + parentTypeName + ")");
			}
			if (fieldName.startsWith("__")) {
				throw new UnsupportedNameException("GraphQL field name cannot start with '__': " + fieldName
					+ " (on type " + parentTypeName + "; double-underscore prefix is reserved for introspection)");
			}
		}

		private boolean registerTypeName(String name, Class<?> clazz) {
			assert name.equals(typeName(clazz)) : "name must equal typeName(clazz)";
			Class<?> existing = typesByName.get(name);
			if (existing == clazz) {
				return false;
			}
			if (existing != null) {
				throw new UnsupportedNameException("Duplicate GraphQL type name '" + name
					+ "' from " + clazz.getName() + " and " + existing.getName());
			}
			typesByName.put(name, clazz);
			return true;
		}

		private static final String KEY_FIELD = "key";
		private static final String VALUE_FIELD = "value";
		private static final String PATH_FIELD = "path";
		private static final String ID_FIELD = "id";
		private static final String TAG_FIELD = "tag";

	}

	@SuppressWarnings("unchecked")
	static <T> T uncheckedCast(Object obj) {
		return (T) obj;
	}

	private static <E extends Entity> E safeGetValue(Listing<E> listing, Identifier id) {
		try {
			return listing.getValue(id);
		} catch (NonexistentReferenceException e) {
			return null;
		}
	}

	/**
	 * @return the name of the GraphQL type to use for {@code type}
	 */
	static @NonNull String typeName(Type type) {
		return switch (type) {
			case Class<?> clazz -> typeName(clazz);
			case ParameterizedType pt -> {
				// This is effectively using Polish notation separated by underscores.
				// It could be ambiguous if type names themselves contained underscores,
				// but in that case we prepend underscore info to the name,
				// rendering it unambiguous.
				var sb = new StringBuilder(typeName(pt.getRawType()));
				for (var arg : pt.getActualTypeArguments()) {
					sb.append('_');
					sb.append(typeName(arg));
				}
				yield sb.toString();
			}
			default -> throw new UnsupportedTypeException("Cannot determine simple name for " + type.getTypeName());
		};
	}

	/**
	 * The type names we use have the property that they can be concatenated
	 * into larger strings separated by underscores unambiguously.
	 * Classes whose names already contain N underscores
	 * will acquire a {@code u{N}} prefix so that, for example,
	 * {@code A_B + C} can be distinguished from {@code A + B-C},
	 * with the former becoming {@code u1A_B_C}
	 * and the latter becoming {@code A_u1B_C}.
	 * To prevent collisions, classes whose names already start with
	 * a {@code u} always get a {@code u{N}} prefix even if N=0.
	 * (In practice, Java class names practically never start with lowercase
	 * letters, so we don't expect this to happen often.)
	 * <p>
	 * When parsing such a name to reconstruct the original type,
	 * the idea would be to walk the string from left to right,
	 * splitting it at underscores, but when you encounter type name
	 * starting with {@code u{N}}: drop that prefix, consider the
	 * next N underscores part of the type name, and continue.
	 *
	 * @return the GraphQL type name to be used for {@code class}
	 * @throws UnsupportedNameException if the name is unsuitable for GraphQL
	 */
	private static @NonNull String typeName(Class<?> clazz) {
		String simpleName = clazz.getSimpleName();
		if (simpleName.indexOf('$') >= 0) {
			throw new UnsupportedNameException("GraphQL type name cannot contain '$': " + simpleName
				+ " (from " + clazz.getName() + ")");
		}
		if (simpleName.startsWith("_")) {
			throw new UnsupportedNameException("GraphQL type name cannot start with '_': " + simpleName);
		}
		int underscores = numOccurrences("_", simpleName);
		if (underscores == 0 && !simpleName.startsWith("u")) {
			return simpleName;
		} else {
			return "u" + underscores + simpleName;
		}
	}

	private static int numOccurrences(String needle, String haystack) {
		int result = 0;
		for (int i = 0; (i = haystack.indexOf(needle, i)) >= 0; i++) {
			result++;
		}
		return result;
	}

	private static <K,V> Map<K,V> hashMapOf(K k1, V v1, K k2, V v2) {
		var result = new HashMap<K,V>();
		result.put(k1, v1);
		result.put(k2, v2);
		return unmodifiableMap(result);
	}

	static final GraphQLScalarType GRAPHQL_IDENTIFIER = GraphQLScalarType.newScalar()
		.name("Identifier")
		.description("A validated Bosk identifier")
		.coercing(new Coercing<Identifier, String>() {
			@Override
			public String serialize(Object dataFetcherResult, GraphQLContext context, Locale locale) {
				if (dataFetcherResult instanceof Identifier id) {
					return id.toString();
				} else {
					throw new CoercingSerializeException("Expected an Identifier");
				}
			}

			@Override
			public Identifier parseValue(Object input, GraphQLContext context, Locale locale) {
				if (input instanceof String s) {
					try {
						return Identifier.from(s);
					} catch (IllegalArgumentException e) {
						throw new CoercingParseValueException("Invalid identifier: " + s, e);
					}
				} else {
					throw new CoercingParseValueException("Expected a string value"
						+ (input == null ? "" : ", got " + input.getClass().getName()));
				}
			}

			@Override
			public Identifier parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
				if (input instanceof StringValue sv && sv.getValue() != null) {
					try {
						return Identifier.from(sv.getValue());
					} catch (IllegalArgumentException e) {
						throw new CoercingParseLiteralException("Invalid identifier: " + sv.getValue(), e);
					}
				} else {
					throw new CoercingParseLiteralException("Expected a string value");
				}
			}
		})
		.build();

	static final GraphQLScalarType GRAPHQL_LONG = GraphQLScalarType.newScalar()
		.name("Long")
		.description("A 64-bit signed integer")
		.coercing(new Coercing<Long, Long>() {
			@Override
			public Long serialize(Object dataFetcherResult, GraphQLContext context, Locale locale) {
				if (dataFetcherResult instanceof Number n) {
					return n.longValue();
				} else {
					throw new CoercingSerializeException("Expected a numeric type");
				}
			}
			@Override
			public Long parseValue(Object input, GraphQLContext context, Locale locale) {
				return switch (input) {
					case Long l -> l;
					case Integer i -> i.longValue();
					case Short s -> s.longValue();
					case Byte b -> b.longValue();
					case BigInteger bi -> {
						try {
							yield bi.longValueExact();
						} catch (ArithmeticException e) {
							throw new CoercingParseValueException("Long value out of range: " + bi);
						}
					}
					case null, default -> throw new CoercingParseValueException("Expected an integer value");
				};
			}
			@Override
			public Long parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
				if (input instanceof IntValue iv) {
					try {
						return iv.getValue().longValueExact();
					} catch (ArithmeticException e) {
						throw new CoercingParseLiteralException("Long value out of range: " + iv.getValue());
					}
				} else {
					throw new CoercingParseLiteralException("Expected an integer value");
				}
			}
		})
		.build();

	static final GraphQLScalarType GRAPHQL_BIG_DECIMAL = GraphQLScalarType.newScalar()
		.name("BigDecimal")
		.description("An arbitrary-precision decimal number")
		.coercing(new Coercing<BigDecimal, String>() {
			@Override
			public String serialize(Object dataFetcherResult, GraphQLContext context, Locale locale) {
				if (dataFetcherResult instanceof BigDecimal bd) {
					return bd.toString();
				} else {
					throw new CoercingSerializeException("Expected a BigDecimal");
				}
			}

			@Override
			public BigDecimal parseValue(Object input, GraphQLContext context, Locale locale) {
				return switch (input) {
					case BigDecimal bd -> bd;
					case Number n -> new BigDecimal(n.toString());
					case String s -> {
						try {
							yield new BigDecimal(s);
						} catch (NumberFormatException e) {
							throw new CoercingParseValueException("Invalid BigDecimal: " + s, e);
						}
					}
					case null, default -> throw new CoercingParseValueException("Expected a string or number");
				};
			}

			@Override
			public BigDecimal parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
				return switch (input) {
					case IntValue iv -> new BigDecimal(iv.getValue());
					case FloatValue fv -> fv.getValue();
					case StringValue sv when sv.getValue() != null -> {
						try {
							yield new BigDecimal(sv.getValue());
						} catch (NumberFormatException e) {
							throw new CoercingParseLiteralException("Invalid BigDecimal: " + sv.getValue(), e);
						}
					}
					case null, default -> throw new CoercingParseLiteralException("Expected a numeric or string value");
				};
			}
		})
		.build();

	static final GraphQLScalarType GRAPHQL_BIG_INTEGER = GraphQLScalarType.newScalar()
		.name("BigInteger")
		.description("An arbitrary-precision integer")
		.coercing(new Coercing<BigInteger, String>() {
			@Override
			public String serialize(Object dataFetcherResult, GraphQLContext context, Locale locale) {
				if (dataFetcherResult instanceof BigInteger bi) {
					return bi.toString();
				} else {
					throw new CoercingSerializeException("Expected a BigInteger");
				}
			}

			@Override
			public BigInteger parseValue(Object input, GraphQLContext context, Locale locale) {
				return switch (input) {
					case BigInteger bi -> bi;
					case Long l -> BigInteger.valueOf(l);
					case Integer i -> BigInteger.valueOf(i);
					case Short s -> BigInteger.valueOf(s);
					case Byte b -> BigInteger.valueOf(b);
					case Number n -> throw new CoercingParseValueException("Expected an integral value, got " + n.getClass().getSimpleName());
					case String s -> {
						try {
							yield new BigInteger(s);
						} catch (NumberFormatException e) {
							throw new CoercingParseValueException("Invalid BigInteger: " + s, e);
						}
					}
					case null, default -> throw new CoercingParseValueException("Expected a BigInteger, integer, or string");
				};
			}

			@Override
			public BigInteger parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
				return switch (input) {
					case IntValue iv -> iv.getValue();
					case StringValue sv when sv.getValue() != null -> {
						try {
							yield new BigInteger(sv.getValue());
						} catch (NumberFormatException e) {
							throw new CoercingParseLiteralException("Invalid BigInteger: " + sv.getValue(), e);
						}
					}
					case null, default -> throw new CoercingParseLiteralException("Expected an integer or string value");
				};
			}
		})
		.build();

	static final GraphQLScalarType GRAPHQL_PATH = GraphQLScalarType.newScalar()
		.name("Path")
		.description("A Bosk reference path string")
		.coercing(new Coercing<Path, String>() {
			@Override
			public String serialize(Object dataFetcherResult, GraphQLContext context, Locale locale) {
				if (dataFetcherResult instanceof Path p) {
					return p.urlEncoded();
				} else {
					throw new CoercingSerializeException("Expected a Path");
				}
			}

			@Override
			public Path parseValue(Object input, GraphQLContext context, Locale locale) {
				if (input instanceof String s) {
					try {
						return Path.parse(s);
					} catch (MalformedPathException e) {
						throw new CoercingParseValueException("Invalid path: " + s, e);
					}
				} else {
					throw new CoercingParseValueException("Expected a string value"
						+ (input == null ? "" : ", got " + input.getClass().getName()));
				}
			}

			@Override
			public Path parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
				if (input instanceof StringValue sv && sv.getValue() != null) {
					try {
						return Path.parse(sv.getValue());
					} catch (MalformedPathException e) {
						throw new CoercingParseLiteralException("Invalid path: " + sv.getValue(), e);
					}
				} else {
					throw new CoercingParseLiteralException("Expected a string value");
				}
			}
		})
		.build();

	private static final Map<Type, GraphQLOutputType> SEED_TYPES = Map.ofEntries(
		Map.entry(String.class, GraphQLString),
		Map.entry(Integer.class, GraphQLInt),
		Map.entry(int.class, GraphQLInt),
		Map.entry(Long.class, GRAPHQL_LONG),
		Map.entry(long.class, GRAPHQL_LONG),
		Map.entry(Float.class, GraphQLFloat),
		Map.entry(float.class, GraphQLFloat),
		Map.entry(Double.class, GraphQLFloat),
		Map.entry(double.class, GraphQLFloat),
		Map.entry(Short.class, GraphQLInt),
		Map.entry(short.class, GraphQLInt),
		Map.entry(Byte.class, GraphQLInt),
		Map.entry(byte.class, GraphQLInt),
		Map.entry(Boolean.class, GraphQLBoolean),
		Map.entry(boolean.class, GraphQLBoolean),
		Map.entry(Character.class, GraphQLString),
		Map.entry(char.class, GraphQLString),
		Map.entry(BigDecimal.class, GRAPHQL_BIG_DECIMAL),
		Map.entry(BigInteger.class, GRAPHQL_BIG_INTEGER),
		Map.entry(Identifier.class, GRAPHQL_IDENTIFIER));

}
