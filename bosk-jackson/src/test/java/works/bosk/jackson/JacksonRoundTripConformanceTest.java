package works.bosk.jackson;

import java.util.List;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import works.bosk.testing.drivers.DriverConformanceTest;

import static works.bosk.AbstractRoundTripTest.jacksonRoundTripFactory;

@ParameterizedClass
@MethodSource("configs")
public class JacksonRoundTripConformanceTest extends DriverConformanceTest {
	JacksonRoundTripConformanceTest(JacksonSerializerConfiguration config) {
		driverFactory = jacksonRoundTripFactory(config);
	}

	static List<JacksonSerializerConfiguration> configs() {
		return List.of(
			JacksonSerializerConfiguration.defaultConfiguration()
		);
	}
}
