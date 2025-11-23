package works.bosk.junit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ParameterizedClass
@ValueSource(ints = {1, 2, 3})
@InjectFrom(ParameterizedClassTest.StringInjector.class)
public class ParameterizedClassTest {
	@Parameter
	int intValue;

	static final Set<Observation> observations = new LinkedHashSet<>();
	record Observation(int intValue, String strValue) {}

	@BeforeAll
	static void setup() {
		observations.clear();
	}

	@InjectedTest
	void testMethod(String strValue) {
		observations.add(new Observation(intValue, strValue));
	}

	@AfterAll
	static void checkObservations() {
		Set<Observation> expected = IntStream.of(1,2,3)
			.boxed()
			.flatMap(i -> Stream.of("A","B","C").map(s -> new Observation(i, s)))
			.collect(toSet());
		assertEquals(expected, observations);
	}

	record StringInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(java.lang.reflect.Parameter parameter) {
			return parameter.getType().equals(String.class);
		}

		@Override
		public List<String> values() {
			return List.of("A", "B", "C");
		}
	}
}
