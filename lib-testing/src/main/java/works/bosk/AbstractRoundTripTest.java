package works.bosk;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonValue;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.jackson.JacksonSerializer;
import works.bosk.jackson.JacksonSerializerConfiguration;

import static java.lang.System.identityHashCode;
import static java.util.Collections.newSetFromMap;
import static tools.jackson.databind.SerializationFeature.INDENT_OUTPUT;

public abstract class AbstractRoundTripTest extends AbstractBoskTest {

	static <R extends Entity> Stream<DriverFactory<R>> driverFactories() {
		return Stream.of(
				directFactory(),
				factoryThatMakesAReference(),

				jacksonRoundTripFactory(JacksonSerializerConfiguration.defaultConfiguration()),

				bsonRoundTripFactory()
		);
	}

	public static <R extends Entity> DriverFactory<R> directFactory() {
		return Bosk.simpleDriver();
	}

	public static <R extends Entity> DriverFactory<R> factoryThatMakesAReference() {
		return (boskInfo, downstream) -> {
			boskInfo.rootReference();
			return Bosk.<R>simpleDriver().build(boskInfo, downstream);
		};
	}

	public static <R extends Entity> DriverFactory<R> jacksonRoundTripFactory(JacksonSerializerConfiguration config) {
		return new JacksonRoundTripDriverFactory<>(config);
	}

	private static class JacksonRoundTripDriverFactory<R extends Entity> implements DriverFactory<R> {
		private final JacksonSerializer jp;

		private JacksonRoundTripDriverFactory(JacksonSerializerConfiguration config) {
			this.jp = new JacksonSerializer();
		}

		@Override
		public BoskDriver build(BoskInfo<R> boskInfo, BoskDriver driver) {
			return new PreprocessingDriver(driver) {
				private final TypeFactory typeFactory = TypeFactory.createDefaultInstance();
				final JacksonModule module = jp.moduleFor(boskInfo);
				final ObjectMapper objectMapper = JsonMapper.builder()
					.addModule(module)
					.enable(INDENT_OUTPUT)
					.build();

				@Override
				protected <T> T preprocess(Reference<T> reference, T newValue) {
					try {
						JavaType targetType = typeFactory.constructType(reference.targetType());
						String json = objectMapper.writerFor(targetType).writeValueAsString(newValue);
						try (var _ = jp.newDeserializationScope(reference)) {
							return objectMapper.readerFor(targetType).readValue(json);
						}
					} catch (JacksonException e) {
						throw new AssertionError(e);
					}
				}

			};
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + identityHashCode(this);
		}
	}

	public static <R extends Entity> DriverFactory<R> bsonRoundTripFactory() {
		return new BsonRoundTripDriverFactory<>();
	}

	@RequiredArgsConstructor
	private static class BsonRoundTripDriverFactory<R extends Entity> implements DriverFactory<R> {
		@Override
		public BoskDriver build(BoskInfo<R> boskInfo, BoskDriver driver) {
			final BsonSerializer bp = new BsonSerializer();
			return new PreprocessingDriver(driver) {
				final CodecRegistry codecRegistry = CodecRegistries.fromProviders(bp.codecProviderFor(boskInfo));

				/**
				 * The shortcomings of the Bson library's type system make this
				 * quite awkward. It's strongly oriented toward writing whole
				 * documents, for some reason, but Bosk supports updates of
				 * individual fields. Therefore, we must wrap the values in
				 * documents containing a single field called "value" of the
				 * type we actually want to process.
				 *
				 * @author pdoyle
				 */
				@Override
				protected <T> T preprocess(Reference<T> reference, T newValue) {
					Codec<T> codec = bp.getCodec(reference.targetType(), reference.targetClass(), codecRegistry, boskInfo);
					BsonDocument document = new BsonDocument();
					try (BsonDocumentWriter writer = new BsonDocumentWriter(document)) {
						writer.writeStartDocument();
						writer.writeName("value");
						codec.encode(writer, newValue, EncoderContext.builder().build());
						writer.writeEndDocument();
					}
					pruneDocument(document.get("value"), reference.targetType(), newSetFromMap(new IdentityHashMap<>()));
					try (BsonDocumentReader reader = new BsonDocumentReader(document)) {
						reader.readStartDocument();
						reader.readName("value");
						T result;
						try (var _ = bp.newDeserializationScope(reference)) {
							result = codec.decode(reader, DecoderContext.builder().build());
						}
						reader.readEndDocument();
						return result;
					}
				}

				private void pruneDocument(BsonValue value, Type nodeType, Set<BsonDocument> alreadyPruned) {
					BsonDocument document;
					if (value instanceof BsonDocument b) {
						document = b;
					} else {
						return;
					}
					if (alreadyPruned.add(document)) {
						Class<?> nodeClass = ReferenceUtils.rawClass(nodeType);
						if (!StateTreeNode.class.isAssignableFrom(nodeClass)) {
							return;
						}
						if (TaggedUnion.class.isAssignableFrom(nodeClass)) {
							return;
						}
						Map<String, RecordComponent> componentsByName = new LinkedHashMap<>();
						for (RecordComponent c: nodeClass.getRecordComponents()) {
							componentsByName.put(c.getName(), c);
						}
						Iterator<Entry<String, BsonValue>> fieldIter = document.entrySet().iterator();
						while (fieldIter.hasNext()) {
							Entry<String, BsonValue> field = fieldIter.next();
							String fieldName = field.getKey();
							String qualifiedName = nodeClass.getSimpleName() + "." + fieldName;
							RecordComponent component = componentsByName.get(fieldName);
							if (component == null) {
								LOGGER.warn("No parameter corresponding to field {}", qualifiedName);
								continue;
							}
							Type pType = component.getGenericType();
							if (Optional.class.isAssignableFrom(component.getType())) {
								if (field.getValue() == null) {
									LOGGER.warn("Pruning Optional.empty() field {} included in BSON", qualifiedName);
									fieldIter.remove();
								} else {
									pType = ReferenceUtils.parameterType(pType, Optional.class, 0);
									pruneDocument(field.getValue(), pType, alreadyPruned);
								}
							} else if (StateTreeSerializer.isEnclosingReference(nodeClass, component)) {
								LOGGER.warn("Pruning enclosing reference {} included in BSON", qualifiedName);
								fieldIter.remove();
							} else {
								pruneDocument(field.getValue(), pType, alreadyPruned);
							}
						}
					} else {
						LOGGER.error("BsonDocument object appears twice in the same document");
					}
				}
			};
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + identityHashCode(this);
		}
	}

	public static abstract class PreprocessingDriver implements BoskDriver {
		private final BoskDriver downstream;

		protected PreprocessingDriver(BoskDriver downstream) {
			this.downstream = downstream;
		}

		@Override
		public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
			downstream.submitConditionalReplacement(target, preprocess(target, newValue), precondition, requiredValue);
		}

		@Override
		public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
			downstream.submitConditionalDeletion(target, precondition, requiredValue);
		}

		@Override
		public <T> void submitReplacement(Reference<T> target, T newValue) {
			downstream.submitReplacement(target, preprocess(target, newValue));
		}

		@Override
		public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
			downstream.submitConditionalCreation(target, preprocess(target, newValue));
		}

		protected abstract <T> T preprocess(Reference<T> reference, T newValue);

		@Override
		public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
			return downstream.initialRoot(rootType);
		}

		@Override
		public <T> void submitDeletion(Reference<T> target) {
			downstream.submitDeletion(target);
		}

		@Override
		public void flush() throws InterruptedException, IOException {
			downstream.flush();
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRoundTripTest.class);

}
