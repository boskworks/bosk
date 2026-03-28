package works.bosk.jackson;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import works.bosk.jackson.JacksonRoundTripConformanceTest.ConfigurationInjector;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;
import works.bosk.junit.Injector;
import works.bosk.testing.drivers.DriverConformanceTest;

import static works.bosk.AbstractRoundTripTest.jacksonRoundTripFactory;

@InjectFields
@InjectFrom(ConfigurationInjector.class)
public class JacksonRoundTripConformanceTest extends DriverConformanceTest {
	@Injected JacksonSerializerConfiguration config;

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = jacksonRoundTripFactory(config);
	}

	record ConfigurationInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == JacksonSerializerConfiguration.class;
		}

		@Override
		public List<?> values() {
			return List.of(
				JacksonSerializerConfiguration.defaultConfiguration()
			);
		}
	}

}
