package works.bosk.jackson;

import java.lang.reflect.Parameter;
import java.util.List;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.ParameterInjector;
import works.bosk.testing.drivers.DriverConformanceTest;

import static works.bosk.AbstractRoundTripTest.jacksonRoundTripFactory;

@InjectFrom(JacksonRoundTripConformanceTest.Injector.class)
public class JacksonRoundTripConformanceTest extends DriverConformanceTest {
	JacksonRoundTripConformanceTest(JacksonSerializerConfiguration config) {
		driverFactory = jacksonRoundTripFactory(config);
	}

	record Injector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType() == JacksonSerializerConfiguration.class;
		}

		@Override
		public List<JacksonSerializerConfiguration> values() {
			return List.of(JacksonSerializerConfiguration.defaultConfiguration());
		}
	}
}
