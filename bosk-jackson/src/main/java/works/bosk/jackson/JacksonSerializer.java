package works.bosk.jackson;

import com.fasterxml.jackson.annotation.JsonFormat;
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
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.KeyDeserializer;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.deser.Deserializers;
import tools.jackson.databind.jsontype.TypeDeserializer;
import tools.jackson.databind.jsontype.TypeSerializer;
import tools.jackson.databind.ser.Serializers;
import tools.jackson.databind.type.CollectionType;
import tools.jackson.databind.type.MapType;
import tools.jackson.databind.type.TypeFactory;
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
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.StateTreeSerializer;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.UnexpectedPathException;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static tools.jackson.core.JsonToken.END_ARRAY;
import static tools.jackson.core.JsonToken.END_OBJECT;
import static tools.jackson.core.JsonToken.START_ARRAY;
import static tools.jackson.core.JsonToken.START_OBJECT;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.ReferenceUtils.rawClass;

/**
 * Provides JSON serialization/deserialization using Jackson.
 * @see StateTreeSerializer
 */
public final class JacksonSerializer extends StateTreeSerializer {
	private final JacksonCompiler compiler = new JacksonCompiler(this);
	private static final TypeFactory typeFactory = TypeFactory.createDefaultInstance();

	public JacksonSerializer() {
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
		private final Map<JavaType, ValueSerializer<?>> memo = new ConcurrentHashMap<>();

		public BoskSerializers(BoskInfo<?> boskInfo) {
			this.boskInfo = boskInfo;
		}

		@Override
		public ValueSerializer<?> findSerializer(SerializationConfig config, JavaType type, BeanDescription.Supplier beanDescRef, JsonFormat.Value formatOverrides) {
			return memo.computeIfAbsent(type, _ -> getValueSerializer(config, type));
		}

		@SuppressWarnings({"rawtypes" })
		private ValueSerializer<?> getValueSerializer(SerializationConfig config, JavaType type) {
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

		private ValueSerializer<Catalog<Entity>> catalogSerializer() {
			return new ValueSerializer<>() {
				@Override
				public void serialize(Catalog<Entity> value, JsonGenerator gen, SerializationContext serializers) {
					writeMapEntries(gen, value.asMap().entrySet(), serializers);
				}
			};
		}

		private ValueSerializer<Listing<Entity>> listingSerializer() {
			return new ValueSerializer<>() {
				@Override
				public void serialize(Listing<Entity> value, JsonGenerator gen, SerializationContext serializers) {
					gen.writeStartObject();

					writeIDsAsArray(value.ids(), gen, serializers);

					gen.writeName("domain");
					serializers
						.findContentValueSerializer(Reference.class, null)
						.serialize(value.domain(), gen, serializers);

					gen.writeEndObject();
				}

				private static void writeIDsAsArray(Collection<Identifier> ids, JsonGenerator gen, SerializationContext serializers) {
					gen.writeName("ids");
					serializers
						.findContentValueSerializer(ID_LIST_TYPE, null)
						.serialize(new ArrayList<>(ids), gen, serializers);
				}

			};
		}

		private ValueSerializer<Reference<?>> referenceSerializer() {
			return new ValueSerializer<>() {
				@Override
				public void serialize(Reference<?> value, JsonGenerator gen, SerializationContext serializers) {
					gen.writeString(value.path().urlEncoded());
				}
			};
		}

		private ValueSerializer<Identifier> identifierSerializer() {
			return new ValueSerializer<>() {
				@Override
				public void serialize(Identifier value, JsonGenerator gen, SerializationContext serializers) {
					gen.writeString(value.toString());
				}
			};
		}

		private ValueSerializer<ListingEntry> listingEntrySerializer() {
			// We serialize ListingEntry as a boolean `true` with the following rationale:
			// - The only "unit type" in JSON is null
			// - `null` is not suitable because many systems treat that as being equivalent to an absent field
			// - Of the other types, boolean seems the most likely to be efficiently processed in every system
			// - `false` gives the wrong impression
			// Hence, by a process of elimination, `true` it is

			return new ValueSerializer<>() {
				@Override
				public void serialize(ListingEntry value, JsonGenerator gen, SerializationContext serializers) {
					gen.writeBoolean(true);
				}
			};
		}

		private ValueSerializer<SideTable<Entity, Object>> sideTableSerializer() {
			return new ValueSerializer<>() {
				@Override
				public void serialize(SideTable<Entity, Object> value, JsonGenerator gen, SerializationContext serializers) {
					gen.writeStartObject();

					gen.writeName("valuesById");
					writeMapEntries(gen, value.idEntrySet(), serializers);

					gen.writeName("domain");
					serializers
						.findContentValueSerializer(Reference.class, null)
						.serialize(value.domain(), gen, serializers);

					gen.writeEndObject();
				}
			};
		}

		@SuppressWarnings({"unchecked"})
		private <T extends VariantCase> ValueSerializer<TaggedUnion<?>> taggedUnionSerializer() {
			return new ValueSerializer<>() {
				/**
				 * A {@link TaggedUnion} has a single field called {@code value},
				 * but we serialize it as though it had a single field whose name equals {@code node.value().tag()} and whose value is {@code node.value()}.
				 */
				@Override
				public void serialize(TaggedUnion<?> union, JsonGenerator gen, SerializationContext serializers) {
					// We assume the TaggedUnion object is correct by construction and don't bother checking the variant case map here
					T variant = (T)union.variant();
					ValueSerializer<Object> valueSerializer = serializers.findValueSerializer(variant.getClass());
					String tag = requireNonNull(variant.tag());
					gen.writeStartObject();
					gen.writeName(tag);
					valueSerializer.serialize(variant, gen, serializers);
					gen.writeEndObject();
				}
			};
		}

		private ValueSerializer<StateTreeNode> stateTreeNodeSerializer(SerializationConfig config, JavaType type) {
			return compiler.<StateTreeNode>compiled(type, boskInfo).serializer(config);
		}

		private ValueSerializer<MapValue<Object>> mapValueSerializer() {
			return new ValueSerializer<>() {
				@Override
				public void serialize(MapValue<Object> value, JsonGenerator gen, SerializationContext serializers) {
					gen.writeStartObject();
					for (Entry<String, Object> element : value.entrySet()) {
						gen.writeName(requireNonNull(element.getKey()));
						Object val = requireNonNull(element.getValue());
						ValueSerializer<Object> valueSerializer = serializers.findValueSerializer(val.getClass());
						valueSerializer.serialize(val, gen, serializers);
					}
					gen.writeEndObject();
				}
			};
		}

		// Thanks but no thanks, Jackson. We don't need your help.


		@Override
		public ValueSerializer<?> findCollectionSerializer(SerializationConfig config, CollectionType type, BeanDescription.Supplier beanDescRef, JsonFormat.Value formatOverrides, TypeSerializer elementTypeSerializer, ValueSerializer<Object> elementValueSerializer) {
			return findSerializer(config, type, beanDescRef, formatOverrides);
		}

		@Override
		public ValueSerializer<?> findMapSerializer(SerializationConfig config, MapType type, BeanDescription.Supplier beanDescRef, JsonFormat.Value formatOverrides, ValueSerializer<Object> keySerializer, TypeSerializer elementTypeSerializer, ValueSerializer<Object> elementValueSerializer) {
			return findSerializer(config, type, beanDescRef, formatOverrides);
		}
	}

	private final class BoskDeserializers extends Deserializers.Base {
		private final BoskInfo<?> boskInfo;
		private final Map<JavaType, ValueDeserializer<?>> memo = new ConcurrentHashMap<>();

		public BoskDeserializers(BoskInfo<?> boskInfo) {
			this.boskInfo = boskInfo;
		}

		@Override
		public ValueDeserializer<?> findBeanDeserializer(JavaType type, DeserializationConfig config, BeanDescription.Supplier beanDescRef) {
			return memo.computeIfAbsent(type, _ -> getValueDeserializer(type, config));
		}

		@Override
		public boolean hasDeserializerFor(DeserializationConfig config, Class<?> valueType) {
			// TODO: Lame
			return null != getValueDeserializer(typeFactory.constructType(valueType), config);
		}

		@SuppressWarnings({"rawtypes" })
		private ValueDeserializer<?> getValueDeserializer(JavaType type, DeserializationConfig config) {
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

		private ValueDeserializer<Catalog<Entity>> catalogDeserializer(JavaType type) {
			JavaType entryType = catalogEntryType(type);

			return new BoskDeserializer<>() {
				@Override
				public Catalog<Entity> deserialize(JsonParser p, DeserializationContext ctxt) {
					LinkedHashMap<Identifier, Entity> entries = readMapEntries(p, entryType, ctxt);
					return Catalog.of(entries.values());
				}
			};
		}

		private ValueDeserializer<Listing<Entity>> listingDeserializer() {
			return new BoskDeserializer<>() {
				@Override
				@SuppressWarnings("unchecked")
				public Listing<Entity> deserialize(JsonParser p, DeserializationContext ctxt) {
					Reference<Catalog<Entity>> domain = null;
					List<Identifier> ids = null;

					expect(START_OBJECT, p);
					while (p.nextToken() != END_OBJECT) {
						p.nextValue();
						switch (p.currentName()) {
							case "ids":
								if (ids != null) {
									throw new StreamReadException(p, "'ids': ids already appeared");
								}
								ids = (List<Identifier>) ctxt
									.findContextualValueDeserializer(ID_LIST_TYPE, null)
									.deserialize(p, ctxt);
								break;
							case "entriesById":
								if (ids != null) {
									throw new StreamReadException(p, "'entriesById': ids already appeared");
								}
								ids = List.copyOf(readMapEntries(p, typeFactory.constructType(Boolean.class), ctxt).keySet());
								break;
							case "domain":
								if (domain != null) {
									throw new StreamReadException(p, "'domain' field appears twice");
								}
								domain = (Reference<Catalog<Entity>>) ctxt
									.findContextualValueDeserializer(CATALOG_REF_TYPE, null)
									.deserialize(p, ctxt);
								break;
							default:
								throw new StreamReadException(p, "Unrecognized field in Listing: " + p.currentName());
						}
					}

					if (domain == null) {
						throw new StreamReadException(p, "Missing 'domain' field");
					} else if (ids == null) {
						throw new StreamReadException(p, "Missing 'ids' field");
					} else {
						return Listing.of(domain, ids);
					}
				}
			};
		}

		private ValueDeserializer<Reference<?>> referenceDeserializer() {
			return new BoskDeserializer<>() {
				@Override
				public Reference<?> deserialize(JsonParser p, DeserializationContext ctxt) {
					try {
						return boskInfo.rootReference().then(Object.class, Path.parse(p.getString()));
					} catch (InvalidTypeException e) {
						throw new UnexpectedPathException(e);
					}
				}
			};
		}

		private ValueDeserializer<Identifier> identifierDeserializer() {
			return new BoskDeserializer<>() {
				@Override
				public Identifier deserialize(JsonParser p, DeserializationContext ctxt) {
					return Identifier.from(p.getString());
				}
			};
		}

		private ValueDeserializer<ListingEntry> listingEntryDeserializer() {
			return new BoskDeserializer<>() {
				@Override
				public ListingEntry deserialize(JsonParser p, DeserializationContext ctxt) {
					if (p.getBooleanValue()) {
						return LISTING_ENTRY;
					} else {
						throw new StreamReadException(p, "Unexpected Listing entry value: " + p.getBooleanValue());
					}
				}
			};
		}

		private ValueDeserializer<SideTable<Entity, Object>> sideTableDeserializer(JavaType type) {
			JavaType valueType = sideTableValueType(type);
			return new BoskDeserializer<>() {
				@Override
				@SuppressWarnings("unchecked")
				public SideTable<Entity, Object> deserialize(JsonParser p, DeserializationContext ctxt) {
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
									throw new StreamReadException(p, "'valuesById' field appears twice");
								}
								break;
							case "domain":
								if (domain == null) {
									domain = (Reference<Catalog<Entity>>) ctxt
										.findContextualValueDeserializer(CATALOG_REF_TYPE, null)
										.deserialize(p, ctxt);
								} else {
									throw new StreamReadException(p, "'domain' field appears twice");
								}
								break;
							default:
								throw new StreamReadException(p, "Unrecognized field in SideTable: " + p.currentName());
						}
					}
					expect(END_OBJECT, p);

					if (domain == null) {
						throw new StreamReadException(p, "Missing 'domain' field");
					} else if (valuesById == null) {
						throw new StreamReadException(p, "Missing 'valuesById' field");
					} else {
						return SideTable.copyOf(domain, valuesById);
					}
				}
			};
		}

		private ValueDeserializer<? extends StateTreeNode> stateTreeNodeDeserializer(JavaType type, DeserializationConfig config) {
			return compiler.<StateTreeNode>compiled(type, boskInfo).deserializer(config);
		}

		private <V extends VariantCase, D extends V> ValueDeserializer<TaggedUnion<V>> taggedUnionDeserializer(JavaType taggedUnionType, DeserializationConfig config) {
			JavaType caseStaticType = taggedUnionType.findTypeParameters(TaggedUnion.class)[0];
			Class<?> caseStaticClass = caseStaticType.getRawClass();
			MapValue<Type> variantCaseMap;
			try {
				variantCaseMap = StateTreeSerializer.getVariantCaseMap(caseStaticClass);
			} catch (InvalidTypeException e) {
				throw new IllegalArgumentException(e);
			}
			Map<String, ValueDeserializer<?>> deserializers = variantCaseMap.entrySet().stream().collect(toMap(Entry::getKey, e ->
				stateTreeNodeDeserializer(typeFactory.constructType(e.getValue()), config)));
			return new ValueDeserializer<>() {
				@Override
				public TaggedUnion<V> deserialize(JsonParser p, DeserializationContext ctxt) {
					expect(START_OBJECT, p);
					if (p.nextToken() == END_OBJECT) {
						throw new StreamReadException(p, "Input is missing variant tag field; expected one of " + variantCaseMap.keySet());
					}
					p.nextValue();

					String tag = p.currentName();
					ValueDeserializer<?> deserializer = deserializers.get(tag);
					if (deserializer == null) {
						throw new StreamReadException(p, "TaggedUnion<" + caseStaticClass.getSimpleName() + "> has unexpected variant tag field \"" + tag + "\"; expected one of " + variantCaseMap.keySet());
					}
					Object deserialized = deserializer.deserialize(p, ctxt);
					@SuppressWarnings("unchecked") Class<D> caseDynamicClass = (Class<D>) rawClass(variantCaseMap.get(tag));
					D value;
					try {
						value = caseDynamicClass.cast(deserialized);
					} catch (ClassCastException e) {
						throw new StreamReadException(p, "Deserialized " + deserialized.getClass().getSimpleName() + " has incorrect tag \"" + tag + "\" corresponding to incompatible type " + caseDynamicClass.getSimpleName());
					}

					p.nextToken();
					expect(END_OBJECT, p);
					return TaggedUnion.of(value);
				}
			};
		}

		private ValueDeserializer<ListValue<Object>> listValueDeserializer(JavaType type) {
			@SuppressWarnings("unchecked")
			Function<Object[], ? extends ListValue<Object>> factory = listValueFactory((Class<ListValue<Object>>)type.getRawClass());
			JavaType arrayType = listValueEquivalentArrayType(type);
			return new BoskDeserializer<>() {
				@Override
				public ListValue<Object> deserialize(JsonParser p, DeserializationContext ctxt) {
					Object[] elementArray = (Object[]) ctxt
						.findContextualValueDeserializer(arrayType, null)
						.deserialize(p, ctxt);
					return factory.apply(elementArray);
				}
			};
		}

		private ValueDeserializer<MapValue<Object>> mapValueDeserializer(JavaType type) {
			JavaType valueType = mapValueValueType(type);
			return new BoskDeserializer<>() {
				@Override
				public MapValue<Object> deserialize(JsonParser p, DeserializationContext ctxt) {
					LinkedHashMap<String, Object> result1 = new LinkedHashMap<>();
					expect(START_OBJECT, p);
					while (p.nextToken() != END_OBJECT) {
						p.nextValue();
						String key = p.currentName();
						Object value = ctxt.findContextualValueDeserializer(valueType, null)
							.deserialize(p, ctxt);
						Object old = result1.put(key, value);
						if (old != null) {
							throw new StreamReadException(p, "MapValue key appears twice: \"" + key + "\"");
						}
					}
					expect(END_OBJECT, p);
					return MapValue.copyOf(result1);
				}
			};
		}

		// Thanks but no thanks, Jackson. We don't need your help.

		@Override
		public ValueDeserializer<?> findMapDeserializer(MapType type, DeserializationConfig config, BeanDescription.Supplier beanDescRef, KeyDeserializer keyDeserializer, TypeDeserializer elementTypeDeserializer, ValueDeserializer<?> elementDeserializer) {
			return findBeanDeserializer(type, config, beanDescRef);
		}

		@Override
		public ValueDeserializer<?> findCollectionDeserializer(CollectionType type, DeserializationConfig config, BeanDescription.Supplier beanDescRef, TypeDeserializer elementTypeDeserializer, ValueDeserializer<?> elementDeserializer) {
			return findBeanDeserializer(type, config, beanDescRef);
		}
	}

	/**
	 * Common properties all our deserializers have.
	 */
	private abstract static class BoskDeserializer<T> extends ValueDeserializer<T> {
		@Override public boolean isCachable() { return true; }
	}

	private <V> void writeMapEntries(JsonGenerator gen, Set<Entry<Identifier,V>> entries, SerializationContext serializers) {
		gen.writeStartArray();
		for (Entry<Identifier, V> entry: entries) {
			gen.writeStartObject();
			gen.writeName(entry.getKey().toString());
			ValueSerializer<Object> valueSerializer = serializers.findContentValueSerializer(entry.getValue().getClass(), null);
			valueSerializer.serialize(entry.getValue(), gen, serializers);
			gen.writeEndObject();
		}
		gen.writeEndArray();
	}

	/**
	 * Leaves the parser sitting on the END_ARRAY token. You could call nextToken() to continue with parsing.
	 */
	private <V> LinkedHashMap<Identifier, V> readMapEntries(JsonParser p, JavaType valueType, DeserializationContext ctxt) {
		@SuppressWarnings("unchecked")
		ValueDeserializer<V> valueDeserializer = (ValueDeserializer<V>) ctxt.findContextualValueDeserializer(valueType, null);
		LinkedHashMap<Identifier, V> result = new LinkedHashMap<>();
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
				throw new StreamReadException(p, "Duplicate sideTable entry '" + fieldName + "'");
			}
		}
		return result;
	}

	private static final JavaType ID_LIST_TYPE = typeFactory.constructType(new TypeReference<
		List<Identifier>>() {});

	private static final JavaType CATALOG_REF_TYPE = typeFactory.constructType(new TypeReference<
		Reference<Catalog<?>>>() {});


	//
	// Helpers
	//

	/**
	 * Returns the fields present in the JSON, with value objects deserialized
	 * using type information from <code>componentsByName</code>.
	 */
	public Map<String, Object> gatherParameterValuesByName(JavaType nodeJavaType, Map<String, RecordComponent> componentsByName, JsonParser p, DeserializationContext ctxt) {
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
					throw new StreamReadException(p, "No such component in record " + nodeClass.getSimpleName() + ": " + name);
				}
			} else {
				JavaType parameterType = typeFactory.resolveMemberType(component.getGenericType(), nodeJavaType.getBindings());
				Object deserializedValue;
				try (@SuppressWarnings("unused") DeserializationScope scope = nodeFieldDeserializationScope(nodeClass, name)) {
					deserializedValue = readField(name, p, ctxt, parameterType);
				}
				Object value = deserializedValue;
				Object prev = parameterValuesByName.put(name, value);
				if (prev != null) {
					throw new StreamReadException(p, "Parameter appeared twice: " + name);
				}
			}
		}
		return parameterValuesByName;
	}

	private Object readField(String name, JsonParser p, DeserializationContext ctxt, JavaType parameterType) {
		// TODO: Combine with similar method in BsonSerializer
		JavaType effectiveType = parameterType;
		Class<?> effectiveClass = effectiveType.getRawClass();
		if (Optional.class.isAssignableFrom(effectiveClass)) {
			// Optional field is present in JSON; wrap deserialized value in Optional.of
			JavaType contentsType = javaParameterType(effectiveType, Optional.class, 0);
			Object deserializedValue = readField(name, p, ctxt, contentsType);
			return Optional.of(deserializedValue);
		} else if (Phantom.class.isAssignableFrom(effectiveClass)) {
			throw new StreamReadException(p, "Unexpected phantom field \"" + name + "\"");
		} else {
			ValueDeserializer<Object> parameterDeserializer = ctxt.findContextualValueDeserializer(effectiveType, null);
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
		return typeFactory.constructArrayType(javaParameterType(listValueType, ListValue.class, 0));
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

	public static void expect(JsonToken expected, JsonParser p) {
		if (p.currentToken() != expected) {
			throw new StreamReadException(p, "Expected " + expected + "; found " + p.currentToken());
		}
	}
}
