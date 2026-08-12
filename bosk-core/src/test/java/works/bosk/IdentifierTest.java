package works.bosk;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.util.List;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;
import works.bosk.junit.Injector;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@InjectedTest
	void unique_withValidPrefix_survivesRoundTrip(String validPrefix) {
		Identifier id = Identifier.unique(validPrefix);
		assertEquals(id, Identifier.from(id.toString()));
		assertTrue(id.toString().startsWith(validPrefix));
	}

	@InjectedTest
	void unique_withInvalidPrefix_throws(@Invalid String invalidPrefix) {
		assertThrows(IllegalArgumentException.class, () -> Identifier.unique(invalidPrefix));
	}

	@Retention(RUNTIME)
	@Target(PARAMETER)
	@interface Invalid {}

	record ValidInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType.equals(String.class)
				&& !element.isAnnotationPresent(Invalid.class);
		}

		@Override
		public List<String> values() {
			return validStrings();
		}
	}

	record InvalidInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType.equals(String.class)
				&& element.isAnnotationPresent(Invalid.class);
		}

		@Override
		public List<String> values() {
			return List.of(
				"",
				"-",
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
