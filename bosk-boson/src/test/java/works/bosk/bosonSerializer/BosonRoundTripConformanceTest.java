package works.bosk.bosonSerializer;

import java.io.IOException;
import java.io.StringWriter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;
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
import works.bosk.testing.drivers.DriverConformanceTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tools.jackson.core.StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION;
import static tools.jackson.databind.cfg.EnumFeature.READ_ENUMS_USING_TO_STRING;
import static tools.jackson.databind.cfg.EnumFeature.WRITE_ENUMS_USING_TO_STRING;

@ParameterizedClass
@EnumSource(BosonRoundTripConformanceTest.Variant.class)
class BosonRoundTripConformanceTest extends DriverConformanceTest {
	private static final TypeFactory typeFactory = TypeFactory.createDefaultInstance();

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
			this.jackson = JsonMapper.builder()
				.enable(INCLUDE_SOURCE_IN_LOCATION)
				.disable(READ_ENUMS_USING_TO_STRING)
				.disable(WRITE_ENUMS_USING_TO_STRING)
				.addModule(new JacksonSerializer().moduleFor(b))
				.build();
		}

		public static <R extends Entity> DriverFactory<R> factory(Variant variant) {
			return (b, d) -> new BosonRoundTripDriver(b, d, variant);
		}

		@Override
		protected <T> T preprocess(Reference<T> reference, T newValue) {
			JavaType referenceType = typeFactory.constructType(reference.targetType());

			JsonValueSpec targetSpec = typeMap.get(DataType.of(reference.targetType()));
			Generator generator = codec.generatorFor(targetSpec);
			Parser parser = codec.parserFor(targetSpec);

			String jsonString;

			if (variant == Variant.J2B) {
				jsonString = generateFromJackson(newValue, referenceType);
			} else {
				jsonString = generateFromBoson(newValue, generator);
				var jacksonString = generateFromJackson(newValue, referenceType);
				JsonNode fromBoson = jackson.readTree(jsonString);
				JsonNode fromJackson = jackson.readTree(jacksonString);
				assertEquals(fromJackson, fromBoson);
			}

			LOGGER.debug("Intermediate JSON:\n{}", jsonString);

			if (variant == Variant.B2J) {
				return jackson.readerFor(referenceType).readValue(jsonString);
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
			return jackson.writerFor(referenceType).writeValueAsString(newValue);
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

	private static final Logger LOGGER = LoggerFactory.getLogger(BosonRoundTripConformanceTest.class);
}
