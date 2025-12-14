package works.bosk;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.BoskConfig.simpleDriver;

public class BuildReferencesErrorTest extends AbstractBoskTest {
	static Bosk<TestRoot> bosk;

	@BeforeAll
	static void setup() {
		bosk = setUpBosk(simpleDriver());
	}

	@Test
	void thisIsForPITest() throws InvalidTypeException {
		// pitest counts initialization failures as a mutation surviving, which is annoying.
		// By doing the setup here in a test method, we ensure the mutations are killed.
		bosk.buildReferences(BuildReferencesTest.Refs.class);
	}

	@Test
	void returnsNonReference_throws() {
		// We're not all that particular about which exception gets thrown
		assertThrows(InvalidTypeException.class, ()->
			bosk.buildReferences(Invalid_NonReference.class));
	}

	@Test
	void missingAnnotation_throws() {
		assertThrows(InvalidTypeException.class, ()->
			bosk.buildReferences(Invalid_NoAnnotation.class));
	}

	@Test
	void wrongReturnType_throws() {
		assertThrows(InvalidTypeException.class, ()->
			bosk.buildReferences(Invalid_WrongType.class));
	}

	@Test
	void unexpectedParameterType_throws() {
		assertThrows(InvalidTypeException.class, ()->
			bosk.buildReferences(Invalid_WeirdParameter.class));
	}

	@SuppressWarnings("unused")
	public interface Invalid_NonReference {
		@ReferencePath("/entities/-entity-")
		String anyEntity();
	}

	@SuppressWarnings("unused")
	public interface Invalid_NoAnnotation {
		Reference<TestEntity> anyEntity();
	}

	@SuppressWarnings("unused")
	public interface Invalid_WrongType {
		@ReferencePath("/entities/-entity-")
		Reference<TestChild> anyEntity();
	}

	@SuppressWarnings("unused")
	public interface Invalid_WeirdParameter {
		@ReferencePath("/entities/-entity-")
		Reference<TestEntity> anyEntity(Object parameter);
	}

}
