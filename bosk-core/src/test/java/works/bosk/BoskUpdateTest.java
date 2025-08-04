package works.bosk;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * To get complete coverage of Bosk.java, include these:
 *
 * @see BoskConstructorTest
 * @see BoskLocalReferenceTest
 * @see HooksTest
 * @see ReferenceTest
 */
public class BoskUpdateTest extends AbstractBoskTest {
	Bosk<TestRoot> bosk;

	Refs refs;

	public interface Refs {
		@ReferencePath("/entities/-entity-") Reference<TestEntity> entity(Identifier entity);
		@ReferencePath("/entities/-entity-/string") Reference<String> entityString(Identifier entity);
		@ReferencePath("/entities/-entity-/children/-child-") Reference<TestChild> child(Identifier entity, Identifier child);
		@ReferencePath("/entities/-entity-/id") Reference<Identifier> entityID(Identifier entity);
	}

	TestRoot originalRoot;
	TestEntity originalParent;
	TestChild originalChild1;

	static final Identifier PARENT_ID = Identifier.from("parent");
	static final Identifier CHILD_1_ID = Identifier.from("child1");
	static final Identifier CHILD_4_ID = Identifier.from("child4"); // Initially nonexistent

	@BeforeEach
	void createBosk() throws InvalidTypeException {
		bosk = new Bosk<>(
			boskName(),
			TestRoot.class,
			AbstractBoskTest::initialRoot,
			Bosk.simpleDriver(),
			Bosk.simpleRegistrar());
		refs = bosk.buildReferences(Refs.class);
		try (var _ = bosk.readContext()) {
			originalRoot = bosk.rootReference().value();
			originalParent = refs.entity(PARENT_ID).value();
			originalChild1 = refs.child(PARENT_ID, CHILD_1_ID).value();
		}
	}

	@Test
	void initialRoot_matches() throws IOException, InterruptedException {
		assertValueEquals(initialRoot(bosk), bosk.rootReference());
	}

	@Test
	void replaceEntity_nodeChanged() throws IOException, InterruptedException {
		TestEntity newValue = originalParent.withString(originalParent.string() + " - modified");
		Reference<TestEntity> ref = refs.entity(originalParent.id());
		bosk.driver().submitReplacement(ref, newValue);
		assertValueEquals(newValue, ref);
	}

	@Test
	void replaceField_valueChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = refs.entityString(originalParent.id());
		bosk.driver().submitReplacement(ref, newValue);
		assertValueEquals(newValue, ref);
	}

	@Test
	void replaceNonexistentField_nothingChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = refs.entityString(Identifier.from("nonexistent"));
		bosk.driver().submitReplacement(ref, newValue);
		assertValueEquals(originalRoot, bosk.rootReference());
		assertValueEquals(null, ref);
	}

	@Test
	void initializeNonexistent_nodeCreated() throws IOException, InterruptedException {
		TestChild newValue = new TestChild(CHILD_4_ID, "string", TestEnum.OK, Catalog.empty());
		Reference<TestChild> ref = refs.child(PARENT_ID, CHILD_4_ID);
		bosk.driver().submitConditionalCreation(ref, newValue);
		assertValueEquals(newValue, ref);
	}

	@Test
	void initializeExisting_nothingChanged() throws IOException, InterruptedException {
		TestChild newValue = new TestChild(CHILD_1_ID, "string", TestEnum.OK, Catalog.empty());
		Reference<TestChild> ref = refs.child(PARENT_ID, CHILD_1_ID);

		// Child 1 already exists, so submitConditionalCreation should have no effect
		bosk.driver().submitConditionalCreation(ref, newValue);
		assertValueEquals(originalRoot, bosk.rootReference());
		assertValueEquals(originalChild1, ref);
	}

	@Test
	void conditionalReplaceIDMatches_valueChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = refs.entityString(originalParent.id());
		Reference<Identifier> idRef = refs.entityID(originalParent.id());
		bosk.driver().submitConditionalReplacement(ref, newValue, idRef, originalParent.id());
		assertValueEquals(newValue, ref);
	}

	@Test
	void conditionalReplaceIDMismatches_nothingChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = refs.entityString(originalParent.id());
		Reference<Identifier> idRef = refs.entityID(originalParent.id());
		bosk.driver().submitConditionalReplacement(ref, newValue, idRef, Identifier.from("nonexistent"));
		assertValueEquals(originalRoot, bosk.rootReference());
		assertValueEquals(originalParent.string(), ref);
	}

	@Test
	void conditionalReplaceIDMismatchesNull_nothingChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = refs.entityString(originalParent.id());
		Reference<Identifier> idRef = refs.entityID(originalParent.id());
		bosk.driver().submitConditionalReplacement(ref, newValue, idRef, null);
		assertValueEquals(originalRoot, bosk.rootReference());
		assertValueEquals(originalParent.string(), ref);
	}

	@Test
	void conditionalReplaceIDMatchesNull_valueChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = refs.entityString(originalParent.id());
		Reference<Identifier> idRef = refs.entityID(Identifier.from("nonexistent"));
		bosk.driver().submitConditionalReplacement(ref, newValue, idRef, null);
		assertValueEquals(newValue, ref);
	}

	@Test
	void conditionalReplaceIDMismatchesNonNull_nothingChanged() throws IOException, InterruptedException {
		String newValue = originalParent.string() + " - modified";
		Reference<String> ref = refs.entityString(originalParent.id());
		Reference<Identifier> idRef = refs.entityID(Identifier.from("nonexistent"));
		bosk.driver().submitConditionalReplacement(ref, newValue, idRef, Identifier.from("someValue"));
		assertValueEquals(originalRoot, bosk.rootReference());
		assertValueEquals(originalParent.string(), ref);
	}

	<T> void assertValueEquals(T expected, Reference<T> ref) throws IOException, InterruptedException {
		bosk.driver().flush();
		try (var _ = bosk.readContext()) {
			assertEquals(expected, ref.valueIfExists());
		}
	}
}
