package works.bosk;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.ReferencePath;
import works.bosk.annotations.VariantCaseMap;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.BoskTestUtils.boskName;

class TaggedUnionTest extends AbstractBoskTest {

	public record BoskState(
		TaggedUnion<TestVariant> v
	) implements StateTreeNode { }

	public interface TestVariant extends VariantCase {
		default String tag() {
			if (this instanceof StringCase) {
				return "string";
			} else {
				return "id";
			}
		}

		@VariantCaseMap
		MapValue<Class<? extends TestVariant>> TYPE_MAP = MapValue.copyOf(Map.of(
			"string", StringCase.class,
			"id", IDCase.class
		));
	}

	public record StringCase(String value) implements TestVariant {}
	public record IDCase(Identifier value) implements TestVariant {}

	public interface Refs {
		@ReferencePath("/v") Reference<TaggedUnion<TestVariant>> v();
		@ReferencePath("/v/id") Reference<IDCase> idCase();
		@ReferencePath("/v/id/value") Reference<Identifier> idValue();
		@ReferencePath("/v/string") Reference<StringCase> stringCase();
		@ReferencePath("/v/string/value") Reference<String> stringValue();
	}

	@Test
	void test() throws InvalidTypeException, IOException, InterruptedException {
		String stringValue = "test";
		Identifier idValue = Identifier.from("test2");
		StringCase stringCase = new StringCase(stringValue);
		IDCase idCase = new IDCase(idValue);
		var bosk = new Bosk<>(boskName(), BoskState.class, _ -> new BoskState(TaggedUnion.of(stringCase)), Bosk.simpleDriver(), Bosk.simpleRegistrar());
		var refs = bosk.rootReference().buildReferences(Refs.class);

		// Initial state
		try (var _ = bosk.readContext()) {
			assertEquals(TaggedUnion.of(stringCase), refs.v().value());
			assertEquals(stringCase, refs.stringCase().value());
			assertEquals(stringValue, refs.stringValue().value());
			assertNull(refs.idCase().valueIfExists());
			assertNull(refs.idValue().valueIfExists());
		}

		bosk.driver().submitReplacement(refs.v(), TaggedUnion.of(idCase));
		bosk.driver().flush();

		try (var _ = bosk.readContext()) {
			assertEquals(TaggedUnion.of(idCase), refs.v().value());
			assertEquals(idCase, refs.idCase().valueIfExists());
			assertEquals(idValue, refs.idValue().valueIfExists());
			assertNull(refs.stringCase().valueIfExists());
			assertNull(refs.stringValue().valueIfExists());
		}

		// Can't update the cases inside the TaggedUnion
		assertThrows(IllegalArgumentException.class, ()->bosk.driver().submitReplacement(refs.stringCase(), stringCase));
		assertThrows(IllegalArgumentException.class, ()->bosk.driver().submitReplacement(refs.idCase(), idCase));
	}

	public interface WrongTagVariant extends VariantCase {
		@VariantCaseMap
		MapValue<Class<? extends WrongTagVariant>> CASE_MAP = MapValue.copyOf(Map.of(
			"expectedTag", NonexistentTagCase.class
		));
	}

	public record NonexistentTagCase() implements WrongTagVariant {
		public NonexistentTagCase {
			// The assertion recommended in the user's guide
			assert CASE_MAP.get(tag()).isInstance(this);
		}

		@Override
		public String tag() {
			return "actualTag";
		}
	}

	public record WrongTypeCase() implements WrongTagVariant {
		public WrongTypeCase {
			// The assertion recommended in the user's guide
			assert CASE_MAP.get(tag()).isInstance(this);
		}

		@Override
		public String tag() {
			return "expectedTag";
		}
	}

	@Test
	void wrongTag_fails() {
		assertThrows(NullPointerException.class, NonexistentTagCase::new);
		assertThrows(AssertionError.class, WrongTypeCase::new);
	}
}
