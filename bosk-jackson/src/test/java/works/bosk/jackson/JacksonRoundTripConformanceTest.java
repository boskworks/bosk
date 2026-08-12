package works.bosk.jackson;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import works.bosk.junit.InjectFields;
import works.bosk.junit.Injected;
import works.bosk.junit.InjectorMethod;
import works.bosk.testing.drivers.DriverConformanceTest;

import static works.bosk.libtesting.AbstractRoundTripTest.jacksonRoundTripFactory;

@InjectFields
public class JacksonRoundTripConformanceTest extends DriverConformanceTest {
	@Injected JacksonSerializerConfiguration config;

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = jacksonRoundTripFactory(config);
	}

	@InjectorMethod
	static Stream<JacksonSerializerConfiguration> configurations() {
		return Stream.of(JacksonSerializerConfiguration.defaultConfiguration());
	}

}
