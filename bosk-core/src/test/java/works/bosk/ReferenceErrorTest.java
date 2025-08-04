package works.bosk;

import java.util.Optional;
import lombok.experimental.FieldNameConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.testing.BoskTestUtils.boskName;

public class ReferenceErrorTest {
	Bosk<?> bosk;

	@BeforeEach
	void setupBosk() {
		bosk = new Bosk<>(
			boskName(),
			BadGetters.class,
			_ -> new BadGetters(Identifier.from("test"), new NestedObject(Optional.of("stringValue"))),
			Bosk.simpleDriver(),
			Bosk.simpleRegistrar());
	}

	@Test
	void referenceGet_brokenGetter_propagatesException() throws InvalidTypeException {
		Reference<Identifier> idRef = bosk.rootReference().then(Identifier.class, Path.just("id"));
		try (var _ = bosk.readContext()) {
			assertThrows(UnsupportedOperationException.class, idRef::value,
				"Reference.value() should propagate the exception as-is");
		}
	}

	@Test
	void referenceUpdate_brokenGetter_propagatesException() throws InvalidTypeException {
		Reference<String> stringRef = bosk.rootReference().then(String.class, Path.of(BadGetters.Fields.nestedObject, NestedObject.Fields.string));
		assertThrows(UnsupportedOperationException.class, ()->
			bosk.driver().submitReplacement(stringRef, "newValue"));
		assertThrows(UnsupportedOperationException.class, ()->
			bosk.driver().submitDeletion(stringRef));
	}

	@FieldNameConstants
	@SuppressWarnings("unused")
	public record BadGetters(Identifier id, NestedObject nestedObject) implements Entity {
		public Identifier id() {
			throw new UnsupportedOperationException("Whoops");
		}

		public NestedObject nestedObject() {
			throw new UnsupportedOperationException("Whoops");
		}
	}

	@FieldNameConstants
	public record NestedObject(Optional<String> string) implements StateTreeNode {}
}
