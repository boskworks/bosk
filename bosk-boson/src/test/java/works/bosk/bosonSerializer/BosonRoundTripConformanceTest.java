package works.bosk.bosonSerializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Parameter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.AbstractRoundTripTest;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.DriverFactory;
import works.bosk.Entity;
import works.bosk.Reference;
import works.bosk.boson.codec.Codec;
import works.bosk.boson.codec.CodecBuilder;
import works.bosk.boson.codec.Generator;
import works.bosk.boson.codec.Parser;
import works.bosk.boson.codec.io.CharArrayJsonReader;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.types.DataType;
import works.bosk.jackson.JacksonSerializer;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.ParameterInjector;
import works.bosk.testing.drivers.DriverConformanceTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@InjectFrom(BosonRoundTripConformanceTest.VariantInjector.class)
class BosonRoundTripConformanceTest extends DriverConformanceTest {
	BosonRoundTripConformanceTest(Variant variant) {
		driverFactory = BosonRoundTripDriver.factory(variant);
	}

	public static class BosonRoundTripDriver extends AbstractRoundTripTest.PreprocessingDriver {
		private final TypeMap typeMap;
		private final Codec codec;
		private final Variant variant;
		private final ObjectMapper jackson;

		private BosonRoundTripDriver(BoskInfo<?> b, BoskDriver d, Variant variant) {
			super(d);
			this.variant = variant;
			var rootType = DataType.of(b.rootReference().targetType());
			TypeScanner.Bundle bundle = new BosonSerializer().bundleFor(b);
			LOGGER.debug("Creating the real TypeScanner now for root type {}", rootType);
			this.typeMap = new TypeScanner(TypeMap.Settings.DEFAULT.withCompiled(false))
				.addBundle(bundle)
				.scan(rootType)
				.build();
			this.codec = CodecBuilder.using(typeMap).build();
			this.jackson = new ObjectMapper();
			jackson.enable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);
			jackson.registerModule(new JacksonSerializer().moduleFor(b));
		}

		public static <R extends Entity> DriverFactory<R> factory(Variant variant) {
			return (b, d) -> new BosonRoundTripDriver(b, d, variant);
		}

		@Override
		protected <T> T preprocess(Reference<T> reference, T newValue) {
			JavaType referenceType = TypeFactory.defaultInstance().constructType(reference.targetType());

			JsonValueSpec targetSpec = typeMap.get(DataType.of(reference.targetType()));
			Generator generator = codec.generatorFor(targetSpec);
			Parser parser = codec.parserFor(targetSpec);

			String jsonString;

			if (variant == Variant.J2B) {
				jsonString = generateFromJackson(newValue, referenceType);
			} else {
				jsonString = generateFromBoson(newValue, generator);
				var jacksonString = generateFromJackson(newValue, referenceType);
				try {
					JsonNode fromBoson = jackson.readTree(jsonString);
					JsonNode fromJackson = jackson.readTree(jacksonString);
					assertEquals(fromJackson, fromBoson);
				} catch (JsonProcessingException e) {
					throw new AssertionError(e);
				}
			}

			LOGGER.debug("Intermediate JSON:\n{}", jsonString);

			if (variant == Variant.B2J) {
				try {
					return jackson.readerFor(referenceType).readValue(jsonString);
				} catch (JsonProcessingException e) {
					throw new AssertionError("Problem reading " + referenceType, e);
				}
			} else {
				try {
					Object parsed = parser.parse(CharArrayJsonReader.forString(jsonString));
					return reference.targetClass().cast(parsed);
				} catch (IOException e) {
					throw new AssertionError("Unexpected exception", e);
				}
			}
		}

		private <T> String generateFromJackson(T newValue, JavaType referenceType) {
			String jsonString;
			try {
				jsonString = jackson.writerFor(referenceType).writeValueAsString(newValue);
			} catch (JsonProcessingException e) {
				throw new AssertionError(e);
			}
			return jsonString;
		}

		private static <T> String generateFromBoson(T newValue, Generator generator) {
			String jsonString;
			var writer = new StringWriter();
			generator.generate(writer, newValue);
			jsonString = writer.toString();
			return jsonString;
		}
	}

	public enum Variant {B2B, J2B, B2J}

	public static final class VariantInjector implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(Variant.class);
		}

		@Override
		public List<Object> values() {
			return List.of(Variant.values());
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(BosonRoundTripConformanceTest.class);
}
