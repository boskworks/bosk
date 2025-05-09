package works.bosk.drivers.mongo.bson;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.bson.BsonReader;
import org.bson.BsonType;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import works.bosk.ReferenceUtils;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.StateTreeSerializer;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.UnexpectedPathException;

import static java.lang.invoke.MethodHandles.collectArguments;
import static java.lang.invoke.MethodHandles.explicitCastArguments;
import static java.lang.invoke.MethodHandles.filterArguments;
import static java.lang.invoke.MethodHandles.guardWithTest;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.permuteArguments;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static works.bosk.ListingEntry.LISTING_ENTRY;
import static works.bosk.ReferenceUtils.getterMethod;
import static works.bosk.ReferenceUtils.parameterType;
import static works.bosk.ReferenceUtils.rawClass;
import static works.bosk.drivers.mongo.bson.BsonFormatter.dottedFieldNameSegment;
import static works.bosk.drivers.mongo.bson.BsonFormatter.undottedFieldNameSegment;
import static works.bosk.util.ReflectionHelpers.boxedClass;

public final class BsonSerializer extends StateTreeSerializer {
	private final ValueCodecProvider valueCodecProvider = new ValueCodecProvider();
	private final Map<Type, Codec<?>> memoizedCodecs = new ConcurrentHashMap<>();

	private static MethodHandle computeFactoryHandle(Constructor<?> constructor) throws AssertionError {
		MethodHandle ctorHandle;
		try {
			ctorHandle = LOOKUP.unreflectConstructor(constructor);
		} catch (IllegalAccessException e) {
			// TODO: Check the `unreflectConstructor` docs and add the necessary checks to make this true
			throw new AssertionError("Shouldn't happen for classes that pass Bosk validation", e);
		}
		return ctorHandle.asSpreader(Object[].class, constructor.getParameterCount());
	}

	/**
	 * Note that the {@link CodecProvider} is {@link Class}-based, so it has
	 * anemic type information for generics due to type erasure. Because of
	 * this, the returned {@link CodecProvider} is unable to provide {@link
	 * Codec}s for certain generic types.
	 *
	 * <p>
	 * In response to this shortcoming, you can access {@link Codec}s for any
	 * type using {@link #getCodec(Type, Class, CodecRegistry, BoskInfo)}
	 */
	public <R extends StateTreeNode> CodecProvider codecProviderFor(BoskInfo<R> boskInfo) {
		return new CodecProvider() {
			public <T> Codec<T> get(Class<T> targetClass, CodecRegistry registry) {
				// Without generic type info, we just use the class as the type;
				// this will throw IllegalArgumentException if that's insufficient
				return getCodec(targetClass, targetClass, registry, boskInfo);
			}
		};
	}

	/**
	 * Like {@link #codecProviderFor(BoskInfo) codecProviderFor(boskInfo)}{@link
	 * CodecProvider#get(Class, CodecRegistry) .get(targetType, registry)}
	 * except this works more broadly because it can accept a {@link
	 * ParameterizedType} for generic classes.
	 *
	 * @param <R> root type of <code>boskInfo</code>
	 * @param targetClass must match <code>targetType</code>. This is provided only to help Java do type inference and avoid ugly and unnecessary type casts.
	 */
	@SuppressWarnings("unchecked")
	public <T, R extends StateTreeNode> Codec<T> getCodec(Type targetType, Class<T> targetClass, CodecRegistry registry, BoskInfo<R> boskInfo) {
		if (rawClass(targetType) != targetClass) {
			throw new IllegalArgumentException("Type does not match Class " + targetClass.getSimpleName() + ": " + targetType);
		}

		if (targetClass.isPrimitive()) {
			// We know from peeking at the Morphia code: the boxed codec works for the unboxed type
			return requireNonNull((Codec<T>) Bson.DEFAULT_CODEC_REGISTRY.get(boxedClass(targetClass)));
		}

		Codec<T> result = (Codec<T>) memoizedCodecs.get(targetType);
		if (result == null) {
			result = computeCodec(targetType, targetClass, registry, boskInfo);
			if (result != null) {
				memoizedCodecs.putIfAbsent(targetType, result);
			}
		}
		return result;
	}

	/**
	 * Most general way to look up a codec. Tries to find a one from this BsonSerializer, using generic type info
	 * if required, and if that fails, falls back to the registry.
	 */
	@SuppressWarnings("unused") // GET_ANY_CODEC
	private <T, R extends StateTreeNode> Codec<T> getAnyCodec(Type targetType, Class<T> targetClass, CodecRegistry registry, BoskInfo<R> boskInfo) {
		Codec<T> result = getCodec(targetType, targetClass, registry, boskInfo);
		if (result == null) {
			return requireNonNull(registry.get(targetClass), "Codec required for " + targetType);
		} else {
			return result;
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" }) // This method is trusted to handle types properly, so other methods can have good strong type checking
	private Codec computeCodec(Type targetType, Class targetClass, CodecRegistry registry, BoskInfo boskInfo) {
		// Classes that we can handle without type parameter info
		//
		if (Identifier.class.isAssignableFrom(targetClass)) {
			return identifierCodec();
		} else if (ListingEntry.class.isAssignableFrom(targetClass)) {
			return listingEntryCodec();
		} else if (Reference.class.isAssignableFrom(targetClass)) {
			return referenceCodec(boskInfo);
		} else if (Enum.class.isAssignableFrom(targetClass)) {
			// Bosk explicitly supports enums
			return enumCodec(targetClass);
		} else if (Listing.class.isAssignableFrom(targetClass)) {
			return listingCodec(targetClass, registry);
		} else if (TaggedUnion.class.isAssignableFrom(targetClass)) {
			return taggedUnionCodec(targetType, targetClass, registry, boskInfo);
		} else if (StateTreeNode.class.isAssignableFrom(targetClass)) {
			// TODO: What about generic node classes?
			return stateTreeNodeCodec(targetClass, registry, boskInfo);
		} else if (Catalog.class.isAssignableFrom(targetClass)) {
			return catalogCodec(targetType, targetClass, registry, boskInfo);
		} else if (SideTable.class.isAssignableFrom(targetClass)) {
			return sideTableCodec(targetType, targetClass, registry, boskInfo);
		} else if (ListValue.class.isAssignableFrom(targetClass)) {
			return listValueCodec(targetType, targetClass, registry, boskInfo);
		} else if (MapValue.class.isAssignableFrom(targetClass)) {
			return mapValueCodec(targetType, targetClass, registry, boskInfo);
		} else if (Optional.class.isAssignableFrom(targetClass)) {
			// Optional.empty() can't be serialized on its own because the field name itself must also be omitted
			throw new IllegalArgumentException("Cannot serialize an Optional on its own; only as a field of another object");
		} else if (Phantom.class.isAssignableFrom(targetClass)) {
			throw new IllegalArgumentException("Cannot serialize a Phantom on its own; only as a field of another object");
		} else if (targetClass.getTypeParameters().length == 0) {
			// The only remaining non-generic types we handle are the primitive values
			return valueCodecProvider.get(targetClass, registry);
		} else {
			// use one of the other codec providers
			return null;
		}
	}

	/**
	 * Generic classes whose Codecs don't need their type parameters.
	 */
	private static final Set<Class<?>> EASYGOING_GENERICS = new HashSet<>(Arrays.asList(
			Reference.class, // These are serialized as text
			Listing.class    // These are serialized as a Reference and a list of Identifiers
			));

	private static Codec<Identifier> identifierCodec() {
		return new Codec<>() {
			@Override public Class<Identifier> getEncoderClass() { return Identifier.class; }

			@Override
			public void encode(BsonWriter writer, Identifier value, EncoderContext encoderContext) {
				writer.writeString(value.toString());
			}

			@Override
			public Identifier decode(BsonReader reader, DecoderContext decoderContext) {
				return Identifier.from(reader.readString());
			}
		};
	}

	private static Codec<ListingEntry> listingEntryCodec() {
		return new Codec<>() {
			@Override public Class<ListingEntry> getEncoderClass() { return ListingEntry.class; }

			@Override
			public void encode(BsonWriter writer, ListingEntry value, EncoderContext encoderContext) {
				writer.writeBoolean(true);
			}

			@Override
			public ListingEntry decode(BsonReader reader, DecoderContext decoderContext) {
				boolean result = reader.readBoolean();
				if (result) {
					return LISTING_ENTRY;
				} else {
					throw new BsonFormatException("Unexpected value for Listing entry: " + result);
				}
			}
		};
	}

	private static <E extends Enum<E>> Codec<E> enumCodec(Class<E> enumClass) {
		return new Codec<>() {
			@Override public Class<E> getEncoderClass() { return enumClass; }

			@Override
			public void encode(BsonWriter writer, E value, EncoderContext encoderContext) {
				writer.writeString(value.name());
			}

			@Override
			public E decode(BsonReader reader, DecoderContext decoderContext) {
				return Enum.valueOf(enumClass, reader.readString());
			}
		};
	}

	private static <E extends Entity> Codec<Listing<E>> listingCodec(Class<Listing<E>> targetClass, CodecRegistry registry) {
		@SuppressWarnings("rawtypes")
		Codec<Reference> referenceCodec = registry.get(Reference.class);
		return new Codec<>() {
			@Override public Class<Listing<E>> getEncoderClass() { return targetClass; }

			@Override
			public void encode(BsonWriter writer, Listing<E> value, EncoderContext encoderContext) {
				writer.writeStartDocument();

				writer.writeName("domain");
				referenceCodec.encode(writer, value.domain(), encoderContext);

				writer.writeName("ids");
				writer.writeStartDocument();
				for (Identifier id: value.ids()) {
					writer.writeName(dottedFieldNameSegment(id.toString()));
					writer.writeBoolean(true);
				}
				writer.writeEndDocument();

				writer.writeEndDocument();
			}

			@Override
			@SuppressWarnings("unchecked")
			public Listing<E> decode(BsonReader reader, DecoderContext decoderContext) {
				if (reader.getCurrentBsonType() == BsonType.DOCUMENT) {
					reader.readStartDocument(); // can't read start document if currentBsonType == "ARRAY"
				}
				reader.readName("domain");
				Reference<Catalog<E>> domain = referenceCodec.decode(reader, decoderContext);

				reader.readName("ids");
				List<Identifier> ids = new ArrayList<>();
				reader.readStartDocument();
				while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
					String id = undottedFieldNameSegment(reader.readName());
					reader.readBoolean();
					ids.add(Identifier.from(id));
				}
				reader.readEndDocument();

				reader.readEndDocument();

				Listing<E> result =  Listing.of(domain, ids);
				if (result.size() > ids.size()) {
					throw new BsonFormatException("Duplicate ids");
				}
				return result;
			}
		};
	}

	private <V> Codec<MapValue<V>> mapValueCodec(Type mapValueType, Class<MapValue<V>> targetClass, CodecRegistry registry, BoskInfo<?> boskInfo) {
		Type valueType = parameterType(mapValueType, MapValue.class, 0);
		@SuppressWarnings("unchecked")
		Class<V> valueClass = (Class<V>) rawClass(valueType);
		Codec<V> valueCodec = getCodec(valueType, valueClass, registry, boskInfo);
		return new Codec<>() {

			@Override
			public Class<MapValue<V>> getEncoderClass() {
				return targetClass;
			}

			@Override
			public void encode(BsonWriter writer, MapValue<V> mapValue, EncoderContext encoderContext) {
				writer.writeStartDocument();
				mapValue.forEach((key, value) ->{
					writer.writeName(key);
					valueCodec.encode(writer, value, encoderContext);
				});
				writer.writeEndDocument();
			}

			@Override
			public MapValue<V> decode(BsonReader reader, DecoderContext decoderContext) {
				Map<String, V> entries = new LinkedHashMap<>();
				reader.readStartDocument();
				while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
					String key = reader.readName();
					V value = valueCodec.decode(reader, decoderContext);
					Object old = entries.put(key, value);
					if (old != null) {
						throw new BsonFormatException("Duplicate keys in MapValue: \"" + key + "\"");
					}
				}
				reader.readEndDocument();
				return MapValue.copyOf(entries);
			}

		};
	}

	private <V> Codec<ListValue<V>> listValueCodec(Type listValueType, Class<ListValue<V>> targetClass, CodecRegistry registry, BoskInfo<?> boskInfo) {
		Function<Object[], ? extends ListValue<V>> factory = listValueFactory(targetClass);
		Type entryType = parameterType(listValueType, ListValue.class, 0);
		@SuppressWarnings("unchecked")
		Class<V> entryClass = (Class<V>) rawClass(entryType);
		Codec<V> entryCodec = getCodec(entryType, entryClass, registry, boskInfo);
		return new Codec<>() {

			@Override
			public Class<ListValue<V>> getEncoderClass() {
				return targetClass;
			}

			@Override
			public void encode(BsonWriter writer, ListValue<V> value, EncoderContext encoderContext) {
				writer.writeStartArray();
				for (V entry: value) {
					entryCodec.encode(writer, entry, encoderContext);
				}
				writer.writeEndArray();
			}

			@Override
			public ListValue<V> decode(BsonReader reader, DecoderContext decoderContext) {
				List<V> entries = new ArrayList<>();
				reader.readStartArray();
				while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
					entries.add(entryCodec.decode(reader, decoderContext));
				}
				reader.readEndArray();
				return factory.apply(entries.toArray((Object[])Array.newInstance(entryClass, entries.size())));
			}

		};
	}

	private static <R extends StateTreeNode> Codec<Reference<?>> referenceCodec(BoskInfo<R> boskInfo) {
		return new Codec<>() {
			@Override @SuppressWarnings({ "rawtypes", "unchecked" })
			public Class<Reference<?>> getEncoderClass() { return (Class)Reference.class; }

			@Override
			public void encode(BsonWriter writer, Reference<?> value, EncoderContext encoderContext) {
				writer.writeString(value.path().urlEncoded());
			}

			@Override
			public Reference<?> decode(BsonReader reader, DecoderContext decoderContext) {
				String urlEncoded = reader.readString();
				try {
					return boskInfo.rootReference().then(Object.class, Path.parse(urlEncoded));
				} catch (InvalidTypeException e) {
					throw new UnexpectedPathException(e);
				}
			}
		};
	}

	private <T extends StateTreeNode, R extends StateTreeNode> Codec<T> stateTreeNodeCodec(Class<T> nodeClass, CodecRegistry registry, BoskInfo<R> boskInfo) {
		// Pre-compute some reflection-based stuff
		//
		Constructor<?> constructor = ReferenceUtils.getCanonicalConstructor(nodeClass);
		LinkedHashMap<String, RecordComponent> parametersByName = Stream.of(nodeClass.getRecordComponents()).collect(toMap(RecordComponent::getName, p->p, (x, y)->{ throw new BsonFormatException("Two record components with same name \"" + x.getName() + "\": " + x + "; " + y); }, LinkedHashMap::new));

		MethodHandle writerHandle = computeAllFieldsWriterHandle(nodeClass, parametersByName, registry, boskInfo);
		MethodHandle factoryHandle = computeFactoryHandle(constructor);

		return new Codec<>() {
			@Override
			public void encode(BsonWriter writer, T value, EncoderContext encoderContext) {
				writer.writeStartDocument();
				try {
					writerHandle.invoke(value, writer, encoderContext);
				} catch (Throwable e) {
					throw new IllegalStateException("Error encoding " + nodeClass + ": " + e.getMessage(), e);
				}
				writer.writeEndDocument();
			}

			@Override
			@SuppressWarnings("unchecked")
			public T decode(BsonReader reader, DecoderContext decoderContext) {
				reader.readStartDocument();
				Map<String, Object> parameterValuesByName = gatherParameterValuesByName(nodeClass, parametersByName, reader, decoderContext, registry, boskInfo);
				reader.readEndDocument();
				List<Object> parameterValues = parameterValueList(nodeClass, parameterValuesByName, parametersByName, boskInfo);
				try {
					return (T) factoryHandle.invoke(parameterValues.toArray());
				} catch (Throwable e) {
					throw new IllegalStateException("Error decoding " + nodeClass.getSimpleName() + ": " + e.getMessage(), e);
				}
			}

			@Override
			public Class<T> getEncoderClass() {
				return nodeClass;
			}
		};
	}

	@SuppressWarnings("unchecked")
	private <V extends VariantCase, R extends StateTreeNode> Codec<TaggedUnion<V>> taggedUnionCodec(Type taggedUnionType, Class<TaggedUnion<V>> taggedUnionClass, CodecRegistry registry, BoskInfo<R> boskInfo) {
		Type caseStaticType = parameterType(taggedUnionType, TaggedUnion.class, 0);
		Class<V> caseStaticClass = (Class<V>)rawClass(caseStaticType);
		MapValue<Type> variantCaseMap;
		try {
			variantCaseMap = StateTreeSerializer.getVariantCaseMap(caseStaticClass);
		} catch (InvalidTypeException e) {
			throw new IllegalArgumentException(e);
		}
		var codecs = variantCaseMap.entrySet().stream().collect(toMap(Entry::getKey, e -> {
			@SuppressWarnings("unchecked")
			Class<? extends StateTreeNode> caseClass = (Class<? extends StateTreeNode>) rawClass(e.getValue());
			return stateTreeNodeCodec(caseClass, registry, boskInfo);
		}));
		return new Codec<>() {
			@Override
			public void encode(BsonWriter writer, TaggedUnion<V> taggedUnion, EncoderContext encoderContext) {
				V variant = taggedUnion.variant();
				String tag = variant.tag();
				@SuppressWarnings("rawtypes")
				Codec caseCodec = codecs.get(tag);
				if (caseCodec == null) {
					throw new IllegalStateException("TaggedUnion<" + caseStaticClass.getSimpleName() + "> has unexpected variant tag field \"" + tag
						+ "; expected one of " + variantCaseMap.keySet());
				}
				Type caseDynamicType = variantCaseMap.get(tag);
				Class<? extends V> caseDynamicClass = (Class<? extends V>) rawClass(caseDynamicType);
				writer.writeStartDocument();
				try {
					writer.writeName(tag);
					caseCodec.encode(writer, caseDynamicClass.cast(variant), encoderContext);
				} catch (Throwable e) {
					throw new IllegalStateException("Error encoding " + caseStaticClass.getSimpleName() + ": " + e.getMessage(), e);
				}
				writer.writeEndDocument();
			}

			@Override
			public TaggedUnion<V> decode(BsonReader reader, DecoderContext decoderContext) {
				reader.readStartDocument();
				String tag = reader.readName();
				@SuppressWarnings("unchecked")
				Codec<V> caseCodec = (Codec<V>) codecs.get(tag);
				if (caseCodec == null) {
					throw new IllegalStateException("Input has unexpected variant tag field \"" + tag
						+ "\" for TaggedUnion<" + caseStaticClass.getSimpleName()
						+ ">; expected one of " + variantCaseMap.keySet());
				}
				Class<? extends V> caseDynamicClass = (Class<? extends V>) rawClass(variantCaseMap.get(tag));
				TaggedUnion<V> result = TaggedUnion.of(caseDynamicClass.cast(caseCodec.decode(reader, decoderContext)));
				if (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
					throw new IllegalStateException("Input has two tags for the same TaggedUnion: \"" + tag + "\" and \"" + reader.readName() + "\"");
				}
				reader.readEndDocument();
				return result;
			}

			@Override
			public Class<TaggedUnion<V>> getEncoderClass() {
				return taggedUnionClass;
			}
		};
	}

	private <E extends Entity, R extends StateTreeNode> Codec<Catalog<E>> catalogCodec(Type catalogType, Class<Catalog<E>> catalogClass, CodecRegistry registry, BoskInfo<R> boskInfo) {
		Type entryType = parameterType(catalogType, Catalog.class, 0);
		@SuppressWarnings("unchecked")
		Class<E> entryClass = (Class<E>) rawClass(entryType).asSubclass(Entity.class);
		Codec<E> entryCodec = getCodec(entryType, entryClass, registry, boskInfo);
		return new Codec<>() {
			@Override public Class<Catalog<E>> getEncoderClass() { return catalogClass; }

			@Override
			public void encode(BsonWriter writer, Catalog<E> value, EncoderContext encoderContext) {
				MethodHandle fieldWriter = catalogWriterHandle(entryClass, registry);
				try {
					fieldWriter.invoke(value, writer, encoderContext);
				} catch (Throwable e) {
					throw new IllegalStateException("Error encoding " + catalogType + ": " + e.getMessage(), e);
				}
			}

			@Override
			public Catalog<E> decode(BsonReader reader, DecoderContext decoderContext) {
				reader.readStartDocument();

				List<E> entries = new ArrayList<>();
				while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
					String fieldName = undottedFieldNameSegment(reader.readName());
					Identifier entryId = Identifier.from(fieldName);
					E entry;
					try (@SuppressWarnings("unused") DeserializationScope s = entryDeserializationScope(entryId)) {
						entry = entryCodec.decode(reader, decoderContext);
					}
					if (entryId.equals(entry.id())) {
						entries.add(entry);
					} else {
						throw new BsonFormatException("Catalog entry ID mismatch: " + entryId + " vs " + entry.id());
					}
				}

				reader.readEndDocument();

				Catalog<E> result =  Catalog.of(entries);
				if (result.size() > entries.size()) {
					throw new BsonFormatException("Duplicate entry IDs in catalog");
				}
				return result;
			}

			private MethodHandle catalogWriterHandle(Class<? extends Entity> entryClass, CodecRegistry codecRegistry) {
				// Curry in the codec suppliers
				return collectArguments(
						WRITE_CATALOG,
						0, codecSupplierHandle(entryClass, codecRegistry, boskInfo));
			}
		};
	}

	private <K extends Entity, V, R extends StateTreeNode> Codec<SideTable<K,V>> sideTableCodec(Type sideTableType, Class<SideTable<K,V>> sideTableClass, CodecRegistry registry, BoskInfo<R> boskInfo) {
		Type valueType = parameterType(sideTableType, SideTable.class, 1);
		@SuppressWarnings("unchecked")
		Class<V> valueClass = (Class<V>) rawClass(valueType);
		Codec<V> valueCodec = getCodec(valueType, valueClass, registry, boskInfo);
		@SuppressWarnings("rawtypes")
		Codec<Reference> referenceCodec = getCodec(Reference.class, Reference.class, registry, boskInfo);

		return new Codec<>() {
			@Override public Class<SideTable<K, V>> getEncoderClass() { return sideTableClass; }

			@Override
			public void encode(BsonWriter writer, SideTable<K, V> value, EncoderContext encoderContext) {
				MethodHandle fieldWriter = sideTableWriterHandle(valueType, registry);
				try {
					fieldWriter.invoke(value, writer, encoderContext);
				} catch (Throwable e) {
					throw new IllegalStateException("Error encoding " + sideTableType + ": " + e.getMessage(), e);
				}
			}

			@Override
			public SideTable<K, V> decode(BsonReader reader, DecoderContext decoderContext) {
				reader.readStartDocument();

				reader.readName("domain");
				@SuppressWarnings("unchecked")
				Reference<Catalog<K>> domain = referenceCodec.decode(reader, decoderContext);

				reader.readName("valuesById");
				LinkedHashMap<Identifier, V> valuesById = new LinkedHashMap<>();
				reader.readStartDocument();
				while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
					String fieldName = undottedFieldNameSegment(reader.readName());
					Identifier id = Identifier.from(fieldName);
					V value;
					try (@SuppressWarnings("unused") DeserializationScope s = entryDeserializationScope(id)) {
						value = valueCodec.decode(reader, decoderContext);
					}
					Object old = valuesById.put(id, value);
					if (old != null) {
						throw new BsonFormatException("Duplicate IDs in sideTable: " + id);
					}
				}
				reader.readEndDocument();

				reader.readEndDocument();

				return SideTable.copyOf(domain, valuesById);
			}

			private MethodHandle sideTableWriterHandle(Type valueType, CodecRegistry codecRegistry) {
				// Curry in the codec suppliers
				return collectArguments(collectArguments(
						WRITE_SIDE_TABLE,
						0, codecSupplierHandle(Reference.class, codecRegistry, boskInfo)),
						0, codecSupplierHandle(valueType, codecRegistry, boskInfo));
			}
		};
	}

	/**
	 * @return Map not necessarily in any particular order; caller is expected to apply any desired ordering.
	 */
	private <R extends StateTreeNode> Map<String, Object> gatherParameterValuesByName(Class<? extends StateTreeNode> nodeClass, Map<String, RecordComponent> componentsByName, BsonReader reader, DecoderContext decoderContext, CodecRegistry registry, BoskInfo<R> boskInfo) {
		Map<String, Object> parameterValuesByName = new HashMap<>();
		while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
			String fieldName = reader.readName();
			RecordComponent component = componentsByName.get(fieldName);
			if (component == null) {
				if (ignoreUnrecognizedField(nodeClass, fieldName)) {
					reader.skipValue();
					continue;
				} else {
					throw new BsonFormatException("Unrecognized field " + fieldName);
				}
			}
			Object value;
			try (@SuppressWarnings("unused") DeserializationScope s = nodeFieldDeserializationScope(nodeClass, fieldName)) {
				value = decodeValue(component.getGenericType(), reader, decoderContext, registry, boskInfo);
			}
			Object old = parameterValuesByName.put(fieldName, value);
			if (old != null) {
				throw new BsonFormatException("Hey, two " + fieldName + " fields");
			}
		}
		return parameterValuesByName;
	}

	private <R extends StateTreeNode> Object decodeValue(Type valueType, BsonReader reader, DecoderContext decoderContext, CodecRegistry registry, BoskInfo<R> boskInfo) {
		Class<?> valueClass = rawClass(valueType);
		Object value;
		if (Phantom.class.isAssignableFrom(valueClass)) {
			throw new BsonFormatException("Unexpected Phantom field");
		} else if (Optional.class.isAssignableFrom(valueClass)) {
			// Optional field is present in BSON; wrap it using Optional.of
			Type contentsType = parameterType(valueType, Optional.class, 0);
			value = Optional.of(decodeValue(contentsType, reader, decoderContext, registry, boskInfo));
		} else {
			value = getCodec(valueType, valueClass, registry, boskInfo).decode(reader, decoderContext);
		}
		return value;
	}

	private <T extends StateTreeNode, R extends StateTreeNode> MethodHandle computeAllFieldsWriterHandle(Class<T> nodeClass, Map<String, RecordComponent> componentsByName, CodecRegistry codecRegistry, BoskInfo<R> boskInfo) {
		MethodHandle handleUnderConstruction = writeNothingHandle(nodeClass);
		for (Entry<String, RecordComponent> e: componentsByName.entrySet()) {
			// Here, handleUnderConstruction has args (N,W,E)
			String name = e.getKey();
			RecordComponent component = e.getValue();
			MethodHandle getter;
			try {
				getter = LOOKUP.unreflect(getterMethod(nodeClass, name));
			} catch (IllegalAccessException | InvalidTypeException e1) {
				throw new IllegalStateException("Error in class " + nodeClass.getSimpleName() + ": " + e1.getMessage(), e1);
			}
			MethodHandle fieldWriter = componentWriterHandle(nodeClass, name, component, codecRegistry, boskInfo); // (P,W,E)
			MethodHandle writerCall = filterArguments(fieldWriter, 0, getter); // (N,W,E)
			MethodHandle nestedCall = collectArguments(writerCall, 0, handleUnderConstruction); // (N,W,E,N,W,E)
			handleUnderConstruction = permuteArguments(nestedCall, writerCall.type(), 0, 1, 2, 0, 1, 2); // (N,W,E)
		}
		return handleUnderConstruction;
	}

	private <R extends StateTreeNode> MethodHandle componentWriterHandle(Class<?> nodeClass, String name, RecordComponent component, CodecRegistry codecRegistry, BoskInfo<R> boskInfo) {
		if (isImplicitParameter(nodeClass, component)) {
			return writeNothingHandle(component.getType());
		} else {
			return valueWriterHandle(name, component.getGenericType(), codecRegistry, boskInfo);
		}
	}

	private <R extends StateTreeNode> MethodHandle valueWriterHandle(String name, Type valueType, CodecRegistry codecRegistry, BoskInfo<R> boskInfo) {
		MethodHandle fieldWriter;
		Class<?> valueClass = rawClass(valueType);
		if (Phantom.class.isAssignableFrom(valueClass)) {
			return writeNothingHandle(valueClass);
		} else if (Optional.class.isAssignableFrom(valueClass)) {
			// Serialize Optional values only when present
			Type contentsType = parameterType(valueType, Optional.class, 0);
			MethodHandle contentsWriter = valueWriterHandle(name, contentsType, codecRegistry, boskInfo);
			MethodHandle unwrapper = filterArguments(contentsWriter, 0, OPTIONAL_GET.asType(OPTIONAL_GET.type().changeReturnType(rawClass(contentsType))));
			fieldWriter = guardWithTest(OPTIONAL_IS_PRESENT, unwrapper, writeNothingHandle(Optional.class));
		} else {
			// Curry in the codec suppliers
			MethodHandle customized = collectArguments(
					insertArguments(WRITE_FIELD, 0, name),
					0, codecSupplierHandle(valueType, codecRegistry, boskInfo));
			fieldWriter = customized.asType(customized.type().changeParameterType(0, valueClass));
		}
		return fieldWriter;
	}

	/**
	 * We can't call {@link #getCodec(Type, Class, CodecRegistry, BoskInfo)
	 * getCodec} during the construction of our Codecs, because there
	 * could be cyclic dependencies; we want to defer the call until
	 * run time, at which point all the Codecs will be known already.
	 *
	 * @return {@link MethodHandle} taking no arguments and returning the desired {@link Codec}.
	 */
	private MethodHandle codecSupplierHandle(Type targetType, CodecRegistry codecRegistry, BoskInfo<?> boskInfo) {
		Class<?> targetClass = rawClass(targetType);
		if (targetClass.getTypeParameters().length >= 1) {
			if ((targetType instanceof ParameterizedType) || EASYGOING_GENERICS.contains(targetClass)) {
				LOGGER.trace("All is well");
			} else {
				// Without this, we get some pretty puzzling exception backtraces
				throw new AssertionError("Class " + targetClass.getSimpleName() + " requires type parameters");
			}
		}
		return insertArguments(GET_ANY_CODEC, 0, this, targetType, targetClass, codecRegistry, boskInfo);
	}

	/**
	 * @return Kind of a null terminator in a chain of writer handles
	 */
	private static MethodHandle writeNothingHandle(Class<?> nodeClass) {
		return explicitCastArguments(WRITE_NOTHING, WRITE_NOTHING.type().changeParameterType(0, nodeClass));
	}

	@SuppressWarnings("unused") // WRITE_FIELD
	private static <F> void writeField(
		String name, Codec<F> codec,                                   // Curried in when the MethodHandle is constructed
		F fieldValue, BsonWriter writer, EncoderContext encoderContext // Supplied when the MethodHandle is invoked
		) {
		writer.writeName(name);
		codec.encode(writer, fieldValue, encoderContext);
	}

	@SuppressWarnings("unused") // WRITE_CATALOG
	private static <E extends Entity> void writeCatalog(
		Codec<E> entryCodec,                                                 // Curried in when the MethodHandle is constructed
		Catalog<E> catalog, BsonWriter writer, EncoderContext encoderContext // Supplied when the MethodHandle is invoked
	) {
		writer.writeStartDocument();

		for (E entry: catalog) {
			writer.writeName(dottedFieldNameSegment(entry.id().toString()));
			entryCodec.encode(writer, entry, encoderContext);
		}

		writer.writeEndDocument();
	}

	@SuppressWarnings("unused") // WRITE_SIDE_TABLE
	private static <K extends Entity, V> void writeSideTable(
		Codec<Reference<?>> referenceCodec, Codec<V> valueCodec,               // Known when the MethodHandle is constructed
		SideTable<K,V> sideTable, BsonWriter writer, EncoderContext encoderContext // Known when the MethodHandle is invoked
		) {
		writer.writeStartDocument();

			writer.writeName("domain");
			referenceCodec.encode(writer, sideTable.domain(), encoderContext);

			writer.writeName("valuesById");
			writer.writeStartDocument();
			for (Entry<Identifier, V> entry: sideTable.idEntrySet()) {
				writer.writeName(dottedFieldNameSegment(entry.getKey().toString()));
				valueCodec.encode(writer, entry.getValue(), encoderContext);
			}
			writer.writeEndDocument();

		writer.writeEndDocument();
	}

	@SuppressWarnings({"unused", "EmptyMethod"}) // WRITE_NOTHING
	private static void writeNothing(Object node, BsonWriter writer, EncoderContext context) {}

	private static final Logger LOGGER = LoggerFactory.getLogger(BsonSerializer.class);

	private static final Lookup LOOKUP = lookup();
	private static final MethodHandle WRITE_FIELD, WRITE_CATALOG, WRITE_SIDE_TABLE, WRITE_NOTHING, GET_ANY_CODEC;
	private static final MethodHandle OPTIONAL_IS_PRESENT, OPTIONAL_GET;


	static {
		try {
			WRITE_FIELD   = LOOKUP.findStatic(BsonSerializer.class, "writeField", methodType(void.class, String.class, Codec.class, Object.class, BsonWriter.class, EncoderContext.class));
			WRITE_CATALOG = LOOKUP.findStatic(BsonSerializer.class, "writeCatalog", methodType(void.class, Codec.class, Catalog.class, BsonWriter.class, EncoderContext.class));
			WRITE_SIDE_TABLE = LOOKUP.findStatic(BsonSerializer.class, "writeSideTable", methodType(void.class, Codec.class, Codec.class, SideTable.class, BsonWriter.class, EncoderContext.class));
			WRITE_NOTHING = LOOKUP.findStatic(BsonSerializer.class, "writeNothing", methodType(void.class, Object.class, BsonWriter.class, EncoderContext.class));
			GET_ANY_CODEC = LOOKUP.findVirtual(BsonSerializer.class, "getAnyCodec", methodType(Codec.class, Type.class, Class.class, CodecRegistry.class, BoskInfo.class));
			OPTIONAL_IS_PRESENT = LOOKUP.findVirtual(Optional.class, "isPresent", methodType(boolean.class));
			OPTIONAL_GET        = LOOKUP.findVirtual(Optional.class, "get", methodType(Object.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new AssertionError("Unexpected failure on MethodHandle lookup", e);
		}
	}

}
