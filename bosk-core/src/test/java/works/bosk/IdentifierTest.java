package works.bosk;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Parameter;
import java.util.List;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;
import works.bosk.junit.ParameterInjector;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@InjectFrom({IdentifierTest.ValidInjector.class, IdentifierTest.InvalidInjector.class})
class IdentifierTest {

	@InjectedTest
	void validString_survivesRoundTrip(String validString) {
		assertEquals(validString, Identifier.from(validString).toString());
	}

	@InjectedTest
	void invalidString_throws(@Invalid String invalidString) {
		assertThrows(IllegalArgumentException.class, () -> Identifier.from(invalidString));
	}

	@Retention(RUNTIME)
	@Target(PARAMETER)
	@interface Invalid {}

	record ValidInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(String.class)
				&& !parameter.isAnnotationPresent(Invalid.class);
		}

		@Override
		public List<String> values() {
			return validStrings();
		}
	}

	record InvalidInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(String.class)
				&& parameter.isAnnotationPresent(Invalid.class);
		}

		@Override
		public List<String> values() {
			return List.of(
				"",
				"-startsWithDash",
				"endsWithDash-",
				"-startsAndEndsWithDash-"
			);
		}
	}

	static List<String> validStrings() {
		return List.of(
			"test",
			"unicode\uD83C\uDF33",
			"name with spaces",
			"name/with/slashes",
			"name.with.dots",
			"name\nwith\nnewlines",
			"name\twith\ttabs"
		);
	}

}
