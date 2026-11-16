package works.bosk;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.ReferencePath;
import works.bosk.annotations.TaggedUnionCaseMap;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.libtesting.AbstractBoskTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.testing.BoskTestUtils.boskName;

class TaggedUnionTest extends AbstractBoskTest {

	public record BoskState(
		TaggedUnion<TestVariant> v
	) implements StateTreeNode { }

	public interface TestVariant extends TaggedUnionCase {
		default String tag() {
			if (this instanceof StringCase) {
				return "string";
			} else {
				return "id";
			}
		}

		@TaggedUnionCaseMap
		MapValue<Class<? extends TestVariant>> TYPE_MAP = MapValue.copyOf(Map.of(
			"string", StringCase.class,
			"id", IDCase.class
		));
	}

	public record StringCase(String value) implements TestVariant {}
	public record IDCase(Identifier value) implements TestVariant {}

	public record CatalogState(TaggedUnion<CatalogVariant> v) implements StateTreeNode { }

	public interface CatalogVariant extends TaggedUnionCase {
		@Override
		String tag();

		@TaggedUnionCaseMap
		MapValue<Class<? extends CatalogVariant>> CASE_MAP =
			MapValue.copyOf(Map.of("catalog", CatalogCase.class));
	}

	public record CatalogCase(Catalog<TestChild> children, Optional<String> optional) implements CatalogVariant {
		@Override
		public String tag() {
			return "catalog";
		}
	}

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
		var bosk = new Bosk<>(boskName(), BoskState.class, _ -> new BoskState(TaggedUnion.of(stringCase)), BoskConfig.simple());
		var refs = bosk.rootReference().buildReferences(Refs.class);

		// Initial state
		try (var _ = bosk.readSession()) {
			assertEquals(TaggedUnion.of(stringCase), refs.v().value());
			assertEquals(stringCase, refs.stringCase().value());
			assertEquals(stringValue, refs.stringValue().value());
			assertNull(refs.idCase().valueIfExists());
			assertNull(refs.idValue().valueIfExists());
		}

		// A field inside the active case is an ordinary node.
		bosk.driver().submitReplacement(refs.stringValue(), "updated");
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertEquals("updated", refs.stringValue().value());
			assertEquals(new StringCase("updated"), refs.stringCase().value());
		}

		// The active case itself can be replaced by another case with the same tag.
		StringCase newStringCase = new StringCase("replaced");
		bosk.driver().submitReplacement(refs.stringCase(), newStringCase);
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertEquals(newStringCase, refs.stringCase().value());
			assertEquals(TaggedUnion.of(newStringCase), refs.v().value());
		}

		// A non-active case is nonexistent, so updating it is a no-op.
		bosk.driver().submitReplacement(refs.idCase(), idCase);
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertNull(refs.idCase().valueIfExists());
			assertEquals(newStringCase, refs.stringCase().value());
		}

		// Switching cases is still done by replacing the union.
		bosk.driver().submitReplacement(refs.v(), TaggedUnion.of(idCase));
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertEquals(TaggedUnion.of(idCase), refs.v().value());
			assertEquals(idCase, refs.idCase().valueIfExists());
			assertEquals(idValue, refs.idValue().valueIfExists());
			assertNull(refs.stringCase().valueIfExists());
			assertNull(refs.stringValue().valueIfExists());
		}

		// The newly active case behaves the same way.
		Identifier newID = Identifier.from("newID");
		bosk.driver().submitReplacement(refs.idValue(), newID);
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertEquals(newID, refs.idValue().value());
			assertEquals(new IDCase(newID), refs.idCase().value());
		}

		IDCase newIDCase = new IDCase(Identifier.from("case"));
		bosk.driver().submitReplacement(refs.idCase(), newIDCase);
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertEquals(newIDCase, refs.idCase().value());
			assertEquals(TaggedUnion.of(newIDCase), refs.v().value());
		}
	}

	@Test
	void updateInsideCase() throws InvalidTypeException, IOException, InterruptedException {
		TestChild child = new TestChild(Identifier.from("child1"), "child1", TestEnum.OK, Catalog.empty());
		CatalogCase initialCase = new CatalogCase(Catalog.of(child), Optional.of("initial"));
		var bosk = new Bosk<>(
			boskName(),
			CatalogState.class,
			_ -> new CatalogState(TaggedUnion.of(initialCase)),
			BoskConfig.simple());

		Reference<String> optional = bosk.rootReference().then(String.class, Path.of("v", "catalog", "optional"));
		Reference<TestChild> childRef = bosk.rootReference().then(TestChild.class, Path.of("v", "catalog", "children", "child1"));
		Reference<CatalogCase> caseRef = bosk.rootReference().then(CatalogCase.class, Path.of("v", "catalog"));

		bosk.driver().submitReplacement(optional, "replaced");
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertEquals("replaced", optional.value());
			assertEquals(new CatalogCase(Catalog.of(child), Optional.of("replaced")), caseRef.value());
		}

		bosk.driver().submitDeletion(childRef);
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertNull(childRef.valueIfExists());
		}

		bosk.driver().submitDeletion(optional);
		bosk.driver().flush();
		try (var _ = bosk.readSession()) {
			assertNull(optional.valueIfExists());
		}

		// The case itself cannot be deleted: a TaggedUnion must have exactly one case.
		assertThrows(IllegalArgumentException.class, () -> bosk.driver().submitDeletion(caseRef));
	}

	public interface WrongTagVariant extends TaggedUnionCase {
		@TaggedUnionCaseMap
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
