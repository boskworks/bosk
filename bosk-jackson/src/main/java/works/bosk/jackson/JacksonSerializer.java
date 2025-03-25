package works.bosk.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.Serializers;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import works.bosk.BoskInfo;
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
import works.bosk.StateTreeSerializer;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.UnexpectedPathException;

import static com.fasterxml.jackson.core.JsonToken.END_ARRAY;
import static com.fasterxml.jackson.core.JsonToken.END_OBJECT;
import static com.fasterxml.jackson.core.JsonToken.START_ARRAY;
import static com.fasterxml.jackson.core.JsonToken.START_OBJECT;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.ReferenceUtils.rawClass;
import static works.bosk.jackson.JacksonSerializerConfiguration.defaultConfiguration;

/**
 * Provides JSON serialization/deserialization using Jackson.
 * @see StateTreeSerializer
 */
public final class JacksonSerializer extends StateTreeSerializer {
	private final JacksonCompiler compiler = new JacksonCompiler(this);
	private final JacksonSerializerConfiguration config;

	public JacksonSerializer() {
		this(defaultConfiguration());
	}

	public JacksonSerializer(JacksonSerializerConfiguration config) {
		this.config = config;
	}

	public BoskJacksonModule moduleFor(BoskInfo<?> boskInfo) {
		return new BoskJacksonModule() {
			@Override
			public void setupModule(SetupContext context) {
				context.addSerializers(new BoskSerializers(boskInfo));
				context.addDeserializers(new BoskDeserializers(boskInfo));
			}
		};
	}

	private final class BoskSerializers extends Serializers.Base {
		private final BoskInfo<?> boskInfo;
		private final Map<JavaType, JsonSerializer<?>> memo = new ConcurrentHashMap<>();

		public BoskSerializers(BoskInfo<?> boskInfo) {
			this.boskInfo = boskInfo;
		}

		@Override
		public JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type, BeanDescription beanDesc) {
			return memo.computeIfAbsent(type, __ -> getJsonSerializer(config, type));
		}

		@SuppressWarnings({"rawtypes" })
		private JsonSerializer<?> getJsonSerializer(SerializationConfig config, JavaType type) {
			Class theClass = type.getRawClass();
			if (Catalog.class.isAssignableFrom(theClass)) {
				return catalogSerializer();
			} else if (Listing.class.isAssignableFrom(theClass)) {
				return listingSerializer();
			} else if (Reference.class.isAssignableFrom(theClass)) {
				return referenceSerializer();
			} else if (Identifier.class.isAssignableFrom(theClass)) {
				return identifierSerializer();
			} else if (ListingEntry.class.isAssignableFrom(theClass)) {
				return listingEntrySerializer();
			} else if (SideTable.class.isAssignableFrom(theClass)) {
				return sideTableSerializer();
			} else if (TaggedUnion.class.isAssignableFrom(theClass)) {
				return taggedUnionSerializer();
			} else if (StateTreeNode.class.isAssignableFrom(theClass)) {
				return stateTreeNodeSerializer(config, type);
			} else if (Optional.class.isAssignableFrom(theClass)) {
				// Optional.empty() can't be serialized on its own because the field name itself must also be omitted
				throw new IllegalArgumentException("Cannot serialize an Optional on its own; only as a field of another object");
			} else if (Phantom.class.isAssignableFrom(theClass)) {
				throw new IllegalArgumentException("Cannot serialize a Phantom on its own; only as a field of another object");
			} else if (MapValue.class.isAssignableFrom(theClass)) {
				return mapValueSerializer();
			} else {
				return null;
			}
		}

		private JsonSerializer<Catalog<Entity>> catalogSerializer() {
			return new JsonSerializer<>() {
				@Override
				public void serialize(Catalog<Entity> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					writeMapEntries(gen, value.asMap().entrySet(), serializers);
				}
			};
		}

		private JsonSerializer<Listing<Entity>> listingSerializer() {
			return new JsonSerializer<>() {
				@Override
				public void serialize(Listing<Entity> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeStartObject();

					switch (config.mapShape()) {
						case ARRAY -> writeIDsAsArray(value.ids(), gen, serializers);
						case LINKED_MAP -> writeIDsAsLinkedMap(value.ids(), gen, serializers);
					}

					gen.writeFieldName("domain");
					serializers
						.findContentValueSerializer(Reference.class, null)
						.serialize(value.domain(), gen, serializers);

					gen.writeEndObject();
				}

				private static void writeIDsAsArray(Collection<Identifier> ids, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeFieldName("ids");
					serializers
						.findContentValueSerializer(ID_LIST_TYPE, null)
						.serialize(new ArrayList<>(ids), gen, serializers);
				}

				private void writeIDsAsLinkedMap(Collection<Identifier> ids, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeFieldName("entriesById");
					var effectiveMapEntries = ids.stream().collect(toMap(
						id -> id,
						id -> true
					)).entrySet();
					writeEntriesAsLinkedMap(gen, effectiveMapEntries, serializers);
				}
			};
		}

		private JsonSerializer<Reference<?>> referenceSerializer() {
			return new JsonSerializer<>() {
				@Override
				public void serialize(Reference<?> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeString(value.path().urlEncoded());
				}
			};
		}

		private JsonSerializer<Identifier> identifierSerializer() {
			return new JsonSerializer<>() {
				@Override
				public void serialize(Identifier value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeString(value.toString());
				}
			};
		}

		private JsonSerializer<ListingEntry> listingEntrySerializer() {
			// We serialize ListingEntry as a boolean `true` with the following rationale:
			// - The only "unit type" in JSON is null
			// - `null` is not suitable because many systems treat that as being equivalent to an absent field
			// - Of the other types, boolean seems the most likely to be efficiently processed in every system
			// - `false` gives the wrong impression
			// Hence, by a process of elimination, `true` it is

			return new JsonSerializer<>() {
				@Override
				public void serialize(ListingEntry value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeBoolean(true);
				}
			};
		}

		private JsonSerializer<SideTable<Entity, Object>> sideTableSerializer() {
			return new JsonSerializer<>() {
				@Override
				public void serialize(SideTable<Entity, Object> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeStartObject();

					gen.writeFieldName("valuesById");
					writeMapEntries(gen, value.idEntrySet(), serializers);

					gen.writeFieldName("domain");
					serializers
						.findContentValueSerializer(Reference.class, null)
						.serialize(value.domain(), gen, serializers);

					gen.writeEndObject();
				}
			};
		}

		@SuppressWarnings({"unchecked"})
		private <T extends VariantCase> JsonSerializer<TaggedUnion<?>> taggedUnionSerializer() {
			return new JsonSerializer<>() {
				/**
				 * A {@link TaggedUnion} has a single field called {@code value},
				 * but we serialize it as though it had a single field whose name equals {@code node.value().tag()} and whose value is {@code node.value()}.
				 */
				@Override
				public void serialize(TaggedUnion<?> union, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					// We assume the TaggedUnion object is correct by construction and don't bother checking the variant case map here
					T variant = (T)union.variant();
					JsonSerializer<Object> valueSerializer = serializers.findValueSerializer(variant.getClass());
					String tag = requireNonNull(variant.tag());
					gen.writeStartObject();
					gen.writeFieldName(tag);
					valueSerializer.serialize(variant, gen, serializers);
					gen.writeEndObject();
				}
			};
		}

		private JsonSerializer<StateTreeNode> stateTreeNodeSerializer(SerializationConfig config, JavaType type) {
			return compiler.<StateTreeNode>compiled(type, boskInfo).serializer(config);
		}

		private JsonSerializer<MapValue<Object>> mapValueSerializer() {
			return new JsonSerializer<>() {
				@Override
				public void serialize(MapValue<Object> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeStartObject();
					for (Entry<String, Object> element : value.entrySet()) {
						gen.writeFieldName(requireNonNull(element.getKey()));
						Object val = requireNonNull(element.getValue());
						JsonSerializer<Object> valueSerializer = serializers.findValueSerializer(val.getClass());
						valueSerializer.serialize(val, gen, serializers);
					}
					gen.writeEndObject();
				}
			};
		}

		// Thanks but no thanks, Jackson. We don't need your help.

		@Override
		public JsonSerializer<?> findCollectionSerializer(SerializationConfig config, CollectionType type, BeanDescription beanDesc, TypeSerializer elementTypeSerializer, JsonSerializer<Object> elementValueSerializer) {
			return findSerializer(config, type, beanDesc);
		}

		@Override
		public JsonSerializer<?> findMapSerializer(SerializationConfig config, MapType type, BeanDescription beanDesc, JsonSerializer<Object> keySerializer, TypeSerializer elementTypeSerializer, JsonSerializer<Object> elementValueSerializer) {
			return findSerializer(config, type, beanDesc);
		}
	}

	private final class BoskDeserializers extends Deserializers.Base {
		private final BoskInfo<?> boskInfo;
		private final Map<JavaType, JsonDeserializer<?>> memo = new ConcurrentHashMap<>();

		public BoskDeserializers(BoskInfo<?> boskInfo) {
			this.boskInfo = boskInfo;
		}

		@Override
		public JsonDeserializer<?> findBeanDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			return memo.computeIfAbsent(type, __ -> getJsonDeserializer(type, config));
		}

		@SuppressWarnings({"rawtypes" })
		private JsonDeserializer<?> getJsonDeserializer(JavaType type, DeserializationConfig config) {
			Class theClass = type.getRawClass();
			if (Catalog.class.isAssignableFrom(theClass)) {
				return catalogDeserializer(type);
			} else if (Listing.class.isAssignableFrom(theClass)) {
				return listingDeserializer();
			} else if (Reference.class.isAssignableFrom(theClass)) {
				return referenceDeserializer();
			} else if (Identifier.class.isAssignableFrom(theClass)) {
				return identifierDeserializer();
			} else if (ListingEntry.class.isAssignableFrom(theClass)) {
				return listingEntryDeserializer();
			} else if (SideTable.class.isAssignableFrom(theClass)) {
				return sideTableDeserializer(type);
			} else if (TaggedUnion.class.isAssignableFrom(theClass)) {
				return taggedUnionDeserializer(type, config);
			} else if (StateTreeNode.class.isAssignableFrom(theClass)) {
				return stateTreeNodeDeserializer(type, config);
			} else if (Optional.class.isAssignableFrom(theClass)) {
				// Optional.empty() can't be serialized on its own because the field name itself must also be omitted
				throw new IllegalArgumentException("Cannot serialize an Optional on its own; only as a field of another object");
			} else if (Phantom.class.isAssignableFrom(theClass)) {
				throw new IllegalArgumentException("Cannot serialize a Phantom on its own; only as a field of another object");
			} else if (ListValue.class.isAssignableFrom(theClass)) {
				return listValueDeserializer(type);
			} else if (MapValue.class.isAssignableFrom(theClass)) {
				return mapValueDeserializer(type);
			} else {
				return null;
			}
		}

		private JsonDeserializer<Catalog<Entity>> catalogDeserializer(JavaType type) {
			JavaType entryType = catalogEntryType(type);

			return new BoskDeserializer<>() {
				@Override
				public Catalog<Entity> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					LinkedHashMap<Identifier, Entity> entries = readMapEntries(p, entryType, ctxt);
					return Catalog.of(entries.values());
				}
			};
		}

		private JsonDeserializer<Listing<Entity>> listingDeserializer() {
			return new BoskDeserializer<>() {
				@Override
				@SuppressWarnings("unchecked")
				public Listing<Entity> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					Reference<Catalog<Entity>> domain = null;
					List<Identifier> ids = null;

					expect(START_OBJECT, p);
					while (p.nextToken() != END_OBJECT) {
						p.nextValue();
						switch (p.currentName()) {
							case "ids":
								if (ids != null) {
									throw new JsonParseException(p, "'ids': ids already appeared");
								}
								ids = (List<Identifier>) ctxt
									.findContextualValueDeserializer(ID_LIST_TYPE, null)
									.deserialize(p, ctxt);
								break;
							case "entriesById":
								if (ids != null) {
									throw new JsonParseException(p, "'entriesById': ids already appeared");
								}
								ids = List.copyOf(readMapEntries(p, TypeFactory.defaultInstance().constructType(Boolean.class), ctxt).keySet());
								break;
							case "domain":
								if (domain != null) {
									throw new JsonParseException(p, "'domain' field appears twice");
								}
								domain = (Reference<Catalog<Entity>>) ctxt
									.findContextualValueDeserializer(CATALOG_REF_TYPE, null)
									.deserialize(p, ctxt);
								break;
							default:
								throw new JsonParseException(p, "Unrecognized field in Listing: " + p.currentName());
						}
					}

					if (domain == null) {
						throw new JsonParseException(p, "Missing 'domain' field");
					} else if (ids == null) {
						throw new JsonParseException(p, "Missing 'ids' field");
					} else {
						return Listing.of(domain, ids);
					}
				}
			};
		}

		private JsonDeserializer<Reference<?>> referenceDeserializer() {
			return new BoskDeserializer<>() {
				@Override
				public Reference<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					try {
						return boskInfo.rootReference().then(Object.class, Path.parse(p.getText()));
					} catch (InvalidTypeException e) {
						throw new UnexpectedPathException(e);
					}
				}
			};
		}

		private JsonDeserializer<Identifier> identifierDeserializer() {
			return new BoskDeserializer<>() {
				@Override
				public Identifier deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					return Identifier.from(p.getText());
				}
			};
		}

		private JsonDeserializer<ListingEntry> listingEntryDeserializer() {
			return new BoskDeserializer<>() {
				@Override
				public ListingEntry deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					if (p.getBooleanValue()) {
						return LISTING_ENTRY;
					} else {
						throw new JsonParseException(p, "Unexpected Listing entry value: " + p.getBooleanValue());
					}
				}
			};
		}

		private JsonDeserializer<SideTable<Entity, Object>> sideTableDeserializer(JavaType type) {
			JavaType valueType = sideTableValueType(type);
			return new BoskDeserializer<>() {
				@Override
				@SuppressWarnings("unchecked")
				public SideTable<Entity, Object> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					Reference<Catalog<Entity>> domain = null;
					LinkedHashMap<Identifier, Object> valuesById = null;

					expect(START_OBJECT, p);
					while (p.nextToken() != END_OBJECT) {
						p.nextValue();
						switch (p.currentName()) {
							case "valuesById":
								if (valuesById == null) {
									valuesById = readMapEntries(p, valueType, ctxt);
								} else {
									throw new JsonParseException(p, "'valuesById' field appears twice");
								}
								break;
							case "domain":
								if (domain == null) {
									domain = (Reference<Catalog<Entity>>) ctxt
										.findContextualValueDeserializer(CATALOG_REF_TYPE, null)
										.deserialize(p, ctxt);
								} else {
									throw new JsonParseException(p, "'domain' field appears twice");
								}
								break;
							default:
								throw new JsonParseException(p, "Unrecognized field in SideTable: " + p.currentName());
						}
					}
					expect(END_OBJECT, p);

					if (domain == null) {
						throw new JsonParseException(p, "Missing 'domain' field");
					} else if (valuesById == null) {
						throw new JsonParseException(p, "Missing 'valuesById' field");
					} else {
						return SideTable.copyOf(domain, valuesById);
					}
				}
			};
		}

		private JsonDeserializer<? extends StateTreeNode> stateTreeNodeDeserializer(JavaType type, DeserializationConfig config) {
			return compiler.<StateTreeNode>compiled(type, boskInfo).deserializer(config);
		}

		private <V extends VariantCase, D extends V> JsonDeserializer<TaggedUnion<V>> taggedUnionDeserializer(JavaType taggedUnionType, DeserializationConfig config) {
			JavaType caseStaticType = taggedUnionType.findTypeParameters(TaggedUnion.class)[0];
			Class<?> caseStaticClass = caseStaticType.getRawClass();
			MapValue<Type> variantCaseMap;
			try {
				variantCaseMap = StateTreeSerializer.getVariantCaseMap(caseStaticClass);
			} catch (InvalidTypeException e) {
				throw new IllegalArgumentException(e);
			}
			Map<String, JsonDeserializer<?>> deserializers = variantCaseMap.entrySet().stream().collect(toMap(Entry::getKey, e ->
				stateTreeNodeDeserializer(TypeFactory.defaultInstance().constructType(e.getValue()), config)));
			return new JsonDeserializer<>() {
				@Override
				public TaggedUnion<V> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					expect(START_OBJECT, p);
					if (p.nextToken() == END_OBJECT) {
						throw new JsonParseException(p, "Input is missing variant tag field; expected one of " + variantCaseMap.keySet());
					}
					p.nextValue();

					String tag = p.currentName();
					JsonDeserializer<?> deserializer = deserializers.get(tag);
					if (deserializer == null) {
						throw new JsonParseException(p, "TaggedUnion<" + caseStaticClass.getSimpleName() + "> has unexpected variant tag field \"" + tag + "\"; expected one of " + variantCaseMap.keySet());
					}
					Object deserialized = deserializer.deserialize(p, ctxt);
					@SuppressWarnings("unchecked") Class<D> caseDynamicClass = (Class<D>) rawClass(variantCaseMap.get(tag));
					D value;
					try {
						value = caseDynamicClass.cast(deserialized);
					} catch (ClassCastException e) {
						throw new JsonParseException(p, "Deserialized " + deserialized.getClass().getSimpleName() + " has incorrect tag \"" + tag + "\" corresponding to incompatible type " + caseDynamicClass.getSimpleName());
					}

					p.nextToken();
					expect(END_OBJECT, p);
					return TaggedUnion.of(value);
				}
			};
		}

		private JsonDeserializer<ListValue<Object>> listValueDeserializer(JavaType type) {
			@SuppressWarnings("unchecked")
			Function<Object[], ? extends ListValue<Object>> factory = listValueFactory((Class<ListValue<Object>>)type.getRawClass());
			JavaType arrayType = listValueEquivalentArrayType(type);
			return new BoskDeserializer<>() {
				@Override
				public ListValue<Object> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					Object[] elementArray = (Object[]) ctxt
						.findContextualValueDeserializer(arrayType, null)
						.deserialize(p, ctxt);
					return factory.apply(elementArray);
				}
			};
		}

		private JsonDeserializer<MapValue<Object>> mapValueDeserializer(JavaType type) {
			JavaType valueType = mapValueValueType(type);
			return new BoskDeserializer<>() {
				@Override
				public MapValue<Object> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					LinkedHashMap<String, Object> result1 = new LinkedHashMap<>();
					expect(START_OBJECT, p);
					while (p.nextToken() != END_OBJECT) {
						p.nextValue();
						String key = p.currentName();
						Object value = ctxt.findContextualValueDeserializer(valueType, null)
							.deserialize(p, ctxt);
						Object old = result1.put(key, value);
						if (old != null) {
							throw new JsonParseException(p, "MapValue key appears twice: \"" + key + "\"");
						}
					}
					expect(END_OBJECT, p);
					return MapValue.copyOf(result1);
				}
			};
		}

		// Thanks but no thanks, Jackson. We don't need your help.

		@Override
		public JsonDeserializer<?> findCollectionDeserializer(CollectionType type, DeserializationConfig config, BeanDescription beanDesc, TypeDeserializer elementTypeDeserializer, JsonDeserializer<?> elementDeserializer) {
			return findBeanDeserializer(type, config, beanDesc);
		}

		@Override
		public JsonDeserializer<?> findMapDeserializer(MapType type, DeserializationConfig config, BeanDescription beanDesc, KeyDeserializer keyDeserializer, TypeDeserializer elementTypeDeserializer, JsonDeserializer<?> elementDeserializer) {
			return findBeanDeserializer(type, config, beanDesc);
		}
	}

	/**
	 * Common properties all our deserializers have.
	 */
	private abstract static class BoskDeserializer<T> extends JsonDeserializer<T> {
		@Override public boolean isCachable() { return true; }
	}

	private <V> void writeMapEntries(JsonGenerator gen, Set<Entry<Identifier,V>> entries, SerializerProvider serializers) throws IOException {
		switch (config.mapShape()) {
			case ARRAY -> writeEntriesAsArray(gen, entries, serializers);
			case LINKED_MAP -> writeEntriesAsLinkedMap(gen, entries, serializers);
		}
	}

	private static <V> void writeEntriesAsArray(JsonGenerator gen, Set<Entry<Identifier, V>> entries, SerializerProvider serializers) throws IOException {
		gen.writeStartArray();
		for (Entry<Identifier, V> entry: entries) {
			gen.writeStartObject();
			gen.writeFieldName(entry.getKey().toString());
			JsonSerializer<Object> valueSerializer = serializers.findContentValueSerializer(entry.getValue().getClass(), null);
			valueSerializer.serialize(entry.getValue(), gen, serializers);
			gen.writeEndObject();
		}
		gen.writeEndArray();
	}

	private static <V> void writeEntriesAsLinkedMap(JsonGenerator gen, Collection<Entry<Identifier, V>> entries, SerializerProvider serializers) throws IOException {
		gen.writeStartObject();
		if (!entries.isEmpty()) {
			if (entries.size() == 1) {
				var entry = entries.iterator().next();
				gen.writeStringField(FIRST, entry.getKey().toString());
				gen.writeStringField(LAST, entry.getKey().toString());
				writeEntryAsField(gen, Optional.empty(), entry, Optional.empty(), serializers);
			} else {
				// This will be so much easier with a list
				List<Entry<Identifier, V>> list = List.copyOf(entries);
				gen.writeStringField(FIRST, list.getFirst().getKey().toString());
				gen.writeStringField(LAST, list.getLast().getKey().toString());
				writeEntryAsField(gen,
					Optional.empty(),
					list.getFirst(),
					Optional.of(list.get(1).getKey()),
					serializers);
				for (int i = 1; i < list.size()-1; i++) {
					writeEntryAsField(gen,
						Optional.of(list.get(i-1).getKey()),
						list.get(i),
						Optional.of(list.get(i+1).getKey()),
						serializers);
				}
				writeEntryAsField(gen,
					Optional.of(list.get(list.size()-2).getKey()),
					list.getLast(),
					Optional.empty(),
					serializers);
			}
		}
		gen.writeEndObject();
	}

	private static <V> void writeEntryAsField(JsonGenerator gen, Optional<Identifier> prev, Entry<Identifier, V> entry, Optional<Identifier> next, SerializerProvider serializers) throws IOException {
		gen.writeFieldName(entry.getKey().toString());
		JsonSerializer<Object> entryDeserializer = serializers.findContentValueSerializer(
			TypeFactory.defaultInstance().constructParametricType(LinkedMapEntry.class, entry.getValue().getClass()),
			null);
		entryDeserializer.serialize(new LinkedMapEntry<>(prev.map(Object::toString), next.map(Object::toString), entry.getValue()), gen, serializers);
	}

	/**
	 * Leaves the parser sitting on the END_ARRAY token. You could call nextToken() to continue with parsing.
	 */
	private <V> LinkedHashMap<Identifier, V> readMapEntries(JsonParser p, JavaType valueType, DeserializationContext ctxt) throws IOException {
		@SuppressWarnings("unchecked")
		JsonDeserializer<V> valueDeserializer = (JsonDeserializer<V>) ctxt.findContextualValueDeserializer(valueType, null);
		LinkedHashMap<Identifier, V> result = new LinkedHashMap<>();
		if (p.currentToken() == START_OBJECT) {
			JsonDeserializer<Object> entryDeserializer = ctxt.findContextualValueDeserializer(
				TypeFactory.defaultInstance().constructParametricType(LinkedMapEntry.class, valueType),
				null);
			HashMap<String, LinkedMapEntry<V>> entries = new HashMap<>();
			String first = null;
			String last = null;
			while (p.nextToken() != END_OBJECT) {
				p.nextValue();
				String fieldName = p.currentName();
				switch (fieldName) {
					case FIRST -> first = p.getText();
					case LAST -> last = p.getText();
					default -> {
						Identifier entryID = Identifier.from(fieldName);
						try (@SuppressWarnings("unused") DeserializationScope scope = entryDeserializationScope(entryID)) {
							@SuppressWarnings("unchecked")
							LinkedMapEntry<V> entry = (LinkedMapEntry<V>) entryDeserializer.deserialize(p, ctxt);
							entries.put(fieldName, entry);
//							p.nextToken();
						}
					}
				}
			}
			String cur = first;
			while (cur != null) {
				LinkedMapEntry<V> entry = entries.get(cur);
				if (entry == null) {
					throw new JsonParseException(p, "No such entry: \"" + cur + "\"");
				}
				result.put(Identifier.from(cur), entry.value());
				String next = entry.next().orElse(null);
				if (next == null && !cur.equals(last)) {
					throw new JsonParseException(p, "Entry \" + cur + \" has no next pointer but does not match last = \" + last + \"");
				}
				// TODO: Verify "prev" pointers
				cur = next;
			}
		} else {
			expect(START_ARRAY, p);
			while (p.nextToken() != END_ARRAY) {
				expect(START_OBJECT, p);
				p.nextValue();
				String fieldName = p.currentName();
				Identifier entryID = Identifier.from(fieldName);
				V value;
				try (@SuppressWarnings("unused") DeserializationScope scope = entryDeserializationScope(entryID)) {
					value = valueDeserializer.deserialize(p, ctxt);
				}
				p.nextToken();
				expect(END_OBJECT, p);

				V oldValue = result.put(entryID, value);
				if (oldValue != null) {
					throw new JsonParseException(p, "Duplicate sideTable entry '" + fieldName + "'");
				}
			}
		}
		return result;
	}

	/**
	 * Structure of the field values used by the {@link JacksonSerializerConfiguration.MapShape#LINKED_MAP LINKED_MAP} format.
	 * @param prev the key corresponding to the previous map entry, or {@link Optional#empty() empty} if none.
	 * @param next the key corresponding to the next map entry, or {@link Optional#empty() empty} if none.
	 * @param value the actual map entry's value
	 */
	public record LinkedMapEntry<V>(
		Optional<String> prev,
		Optional<String> next,
		V value
	) implements StateTreeNode {}

	private static final JavaType ID_LIST_TYPE = TypeFactory.defaultInstance().constructType(new TypeReference<
		List<Identifier>>() {});

	private static final JavaType CATALOG_REF_TYPE = TypeFactory.defaultInstance().constructType(new TypeReference<
		Reference<Catalog<?>>>() {});


	//
	// Helpers
	//

	/**
	 * Returns the fields present in the JSON, with value objects deserialized
	 * using type information from <code>componentsByName</code>.
	 */
	public Map<String, Object> gatherParameterValuesByName(JavaType nodeJavaType, Map<String, RecordComponent> componentsByName, JsonParser p, DeserializationContext ctxt) throws IOException {
		Class<?> nodeClass = nodeJavaType.getRawClass();
		Map<String, Object> parameterValuesByName = new HashMap<>();
		expect(START_OBJECT, p);
		while (p.nextToken() != END_OBJECT) {
			p.nextValue();
			String name = p.currentName();
			RecordComponent component = componentsByName.get(name);
			if (component == null) {
				if (ignoreUnrecognizedField(nodeClass, name)) {
					p.skipChildren();
				} else {
					throw new JsonParseException(p, "No such component in record " + nodeClass.getSimpleName() + ": " + name);
				}
			} else {
				JavaType parameterType = TypeFactory.defaultInstance().resolveMemberType(component.getGenericType(), nodeJavaType.getBindings());
				Object deserializedValue;
				try (@SuppressWarnings("unused") DeserializationScope scope = nodeFieldDeserializationScope(nodeClass, name)) {
					deserializedValue = readField(name, p, ctxt, parameterType);
				}
				Object value = deserializedValue;
				Object prev = parameterValuesByName.put(name, value);
				if (prev != null) {
					throw new JsonParseException(p, "Parameter appeared twice: " + name);
				}
			}
		}
		return parameterValuesByName;
	}

	private Object readField(String name, JsonParser p, DeserializationContext ctxt, JavaType parameterType) throws IOException {
		// TODO: Combine with similar method in BsonSerializer
		JavaType effectiveType = parameterType;
		Class<?> effectiveClass = effectiveType.getRawClass();
		if (Optional.class.isAssignableFrom(effectiveClass)) {
			// Optional field is present in JSON; wrap deserialized value in Optional.of
			JavaType contentsType = javaParameterType(effectiveType, Optional.class, 0);
			Object deserializedValue = readField(name, p, ctxt, contentsType);
			return Optional.of(deserializedValue);
		} else if (Phantom.class.isAssignableFrom(effectiveClass)) {
			throw new JsonParseException(p, "Unexpected phantom field \"" + name + "\"");
		} else {
			JsonDeserializer<Object> parameterDeserializer = ctxt.findContextualValueDeserializer(effectiveType, null);
			return parameterDeserializer.deserialize(p, ctxt);
		}
	}

	private static JavaType catalogEntryType(JavaType catalogType) {
		return javaParameterType(catalogType, Catalog.class, 0);
	}

	private static JavaType sideTableValueType(JavaType sideTableType) {
		return javaParameterType(sideTableType, SideTable.class, 1);
	}

	private static JavaType listValueEquivalentArrayType(JavaType listValueType) {
		return TypeFactory.defaultInstance().constructArrayType(javaParameterType(listValueType, ListValue.class, 0));
	}

	private static JavaType mapValueValueType(JavaType mapValueType) {
		return javaParameterType(mapValueType, MapValue.class, 0);
	}

	public static JavaType javaParameterType(JavaType parameterizedType, Class<?> expectedClass, int index) {
		try {
			return parameterizedType.findTypeParameters(expectedClass)[index];
		} catch (IndexOutOfBoundsException e) {
			throw new IllegalStateException("Error computing javaParameterType(" + parameterizedType + ", " + expectedClass + ", " + index + ")");
		}
	}

	public static void expect(JsonToken expected, JsonParser p) throws IOException {
		if (p.currentToken() != expected) {
			throw new JsonParseException(p, "Expected " + expected + "; found " + p.currentToken());
		}
	}

	private static final String FIRST = "-first";
	private static final String LAST = "-last";
}
