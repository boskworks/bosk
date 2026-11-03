package works.bosk;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.Spliterator;
import java.util.TreeSet;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;
import works.bosk.junit.Injector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@InjectFrom({IntSetTest.Contents.class, IntSetTest.ScenarioInjector.class})
class IntSetTest {
	private static final int[] PROBES = {
		0, 1, 2, 30, 31, 32, 33, 42, 62, 63, 64, 65, 127, 128, 129, 10_000, 1_000_000, Integer.MAX_VALUE
	};

	/**
	 * Curated contents that exercise the sparse, dense, and mixed representations.
	 */
	enum Contents {
		EMPTY,
		SINGLETON,
		DENSE_LOW,
		DENSE_LARGE,
		SPARSE,
		MIXED,
		BOUNDARIES,
		FULL_RANGE;

		void applyTo(Set<Integer> target) {
			switch (this) {
				case EMPTY -> { }
				case SINGLETON -> target.add(42);
				case DENSE_LOW -> {
					for (int i = 0; i < 64; i++) {
						target.add(i);
					}
				}
				case DENSE_LARGE -> {
					for (int i = 0; i < 10_000; i++) {
						target.add(i);
					}
				}
				case SPARSE -> {
					for (int i = 0; i < 20; i++) {
						target.add(i * 1_000_003);
					}
				}
				case MIXED -> {
					for (int i = 0; i < 64; i++) {
						target.add(i);
					}
					target.add(10_000_000);
				}
				case BOUNDARIES -> target.addAll(List.of(0, 1, 30, 31, 32, 33, 62, 63, 64, 65, 127, 128, 129));
				case FULL_RANGE -> target.addAll(List.of(1_000_000, 1_000_000_000, Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
			}
		}
	}

	record Scenario(String name, Contents initial, Distribution distribution, int universe, long seed, int steps) {
		@Override
		public String toString() {
			return name;
		}
	}

	enum Distribution {
		UNIFORM {
			@Override
			int next(Random random, int universe) {
				return random.nextInt(universe);
			}
		},
		DENSE_PREFIX {
			@Override
			int next(Random random, int universe) {
				return random.nextInt(Math.min(64, universe));
			}
		},
		MIXED {
			@Override
			int next(Random random, int universe) {
				return random.nextInt(10) < 8 ? random.nextInt(64) : random.nextInt(universe);
			}
		},
		SPARSE {
			@Override
			int next(Random random, int universe) {
				return random.nextInt(1 + universe / 100_000) * 100_000;
			}
		},
		FULL_RANGE {
			@Override
			int next(Random random, int universe) {
				return random.nextInt() & Integer.MAX_VALUE;
			}
		};

		abstract int next(Random random, int universe);
	}

	record ScenarioInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType.equals(Scenario.class);
		}

		@Override
		public List<?> values() {
			return List.of(
				new Scenario("uniform-small", Contents.EMPTY, Distribution.UNIFORM, 64, 1L, 5_000),
				new Scenario("uniform-medium", Contents.EMPTY, Distribution.UNIFORM, 1_024, 2L, 5_000),
				new Scenario("dense", Contents.DENSE_LOW, Distribution.DENSE_PREFIX, 10_000, 3L, 2_000),
				new Scenario("mixed", Contents.MIXED, Distribution.MIXED, 10_000, 4L, 5_000),
				new Scenario("sparse", Contents.SPARSE, Distribution.SPARSE, 1_000_000, 5L, 3_000),
				new Scenario("full-range", Contents.FULL_RANGE, Distribution.FULL_RANGE, Integer.MAX_VALUE, 6L, 3_000)
			);
		}
	}

	enum Operation {
		ADD,
		REMOVE,
		CONTAINS,
		CLEAR;

		Boolean apply(Set<Integer> target, int value) {
			return switch (this) {
				case ADD -> target.add(value);
				case REMOVE -> target.remove(value);
				case CONTAINS -> target.contains(value);
				case CLEAR -> {
					target.clear();
					yield null;
				}
			};
		}
	}

	private static final Operation[] OPERATIONS = {
		Operation.ADD, Operation.ADD, Operation.ADD, Operation.ADD,
		Operation.REMOVE, Operation.REMOVE, Operation.REMOVE, Operation.REMOVE,
		Operation.CONTAINS, Operation.CONTAINS,
		Operation.CLEAR
	};

	@InjectedTest
	void sizeAndContent_matchTreeSet(Contents contents) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		assertEquals(expected.size(), actual.size());
		assertEquals(expected.isEmpty(), actual.isEmpty());
		assertEquals(expected, actual);
		actual.assertInvariants();
	}

	@InjectedTest
	void iterationAscending_matchesTreeSet(Contents contents) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		assertSameElements(expected, actual, contents.toString());
		actual.assertInvariants();
	}

	@InjectedTest
	void add_matchesTreeSet(Contents contents) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		for (int value : PROBES) {
			boolean expectedAdded = expected.add(value);
			boolean actualAdded = actual.add(value);
			assertEquals(expectedAdded, actualAdded, "add(" + value + ") must return the same result as TreeSet");
			assertEquals(expected, actual);
			actual.assertInvariants();
		}
	}

	@InjectedTest
	void remove_matchesTreeSet(Contents contents) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		for (int value : PROBES) {
			boolean expectedRemoved = expected.remove(value);
			boolean actualRemoved = actual.remove(value);
			assertEquals(expectedRemoved, actualRemoved, "remove(" + value + ") must return the same result as TreeSet");
			assertEquals(expected, actual);
			actual.assertInvariants();
		}

		while (!expected.isEmpty()) {
			int value = expected.first();
			boolean expectedRemoved = expected.remove(value);
			boolean actualRemoved = actual.remove(value);
			assertEquals(expectedRemoved, actualRemoved, "remove(" + value + ") must return the same result as TreeSet");
			assertEquals(expected.size(), actual.size());
			actual.assertInvariants();
		}
		assertTrue(actual.isEmpty());
	}

	@InjectedTest
	void contains_matchesTreeSet(Contents contents) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		for (int value : PROBES) {
			assertEquals(expected.contains(value), actual.contains(value), "contains(" + value + ") must return the same result as TreeSet");
		}
		assertFalse(actual.contains(-1));
		assertFalse(actual.contains(null));
	}

	@InjectedTest
	void clear_empties(Contents contents) {
		IntSet actual = new IntSet();
		contents.applyTo(actual);

		actual.clear();

		assertTrue(actual.isEmpty());
		assertEquals(0, actual.size());
		assertEquals(Set.of(), actual);
		actual.assertInvariants();
	}

	@InjectedTest
	void iteratorRemoval_matchesTreeSet(Contents contents) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		Iterator<Integer> expectedIterator = expected.iterator();
		Iterator<Integer> actualIterator = actual.iterator();
		boolean remove = false;
		while (expectedIterator.hasNext()) {
			assertEquals(expectedIterator.next(), actualIterator.next());
			if (remove) {
				expectedIterator.remove();
				actualIterator.remove();
				actual.assertInvariants();
			}
			remove = !remove;
		}
		assertSameElements(expected, actual, contents.toString());
		actual.assertInvariants();
	}

	@InjectedTest
	void toArray_isIndependentCopy(Contents contents) {
		IntSet actual = new IntSet();
		contents.applyTo(actual);
		Object[] snapshot = actual.toArray();

		Object[] copy = actual.toArray();
		assertArrayEquals(snapshot, copy);
		if (copy.length > 0) {
			copy[0] = -1;
		}
		assertArrayEquals(snapshot, actual.toArray(), "toArray must return a copy");

		Integer[] typed = actual.toArray(new Integer[0]);
		assertEquals(snapshot.length, typed.length);
	}

	@InjectedTest
	void spliterator_isOrderedAndMatchesIteration(Contents contents) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		Spliterator<Integer> spliterator = actual.spliterator();
		assertTrue(spliterator.hasCharacteristics(Spliterator.DISTINCT));
		assertTrue(spliterator.hasCharacteristics(Spliterator.ORDERED));
		assertTrue(spliterator.hasCharacteristics(Spliterator.SIZED));
		assertEquals(new ArrayList<>(expected), StreamSupport.stream(spliterator, false).toList());
	}

	@InjectedTest
	void forEachInt_matchesIteration(Contents contents) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		List<Integer> visited = new ArrayList<>();
		actual.forEachInt(visited::add);
		assertEquals(new ArrayList<>(expected), visited);
	}

	@InjectedTest
	void bulkOperations_matchTreeSet(Contents contents) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		Set<Integer> toAdd = Set.of(3, 31, 32, 1_000_000, Integer.MAX_VALUE);
		assertEquals(expected.addAll(toAdd), actual.addAll(toAdd));
		assertSameElements(expected, actual, "addAll");

		Set<Integer> toRemove = Set.of(0, 1, 32, 64, 1_000_000);
		assertEquals(expected.removeAll(toRemove), actual.removeAll(toRemove));
		assertSameElements(expected, actual, "removeAll");

		Set<Integer> toRetain = Set.of(31, 33, 127, 128, 10_000_000);
		assertEquals(expected.retainAll(toRetain), actual.retainAll(toRetain));
		assertSameElements(expected, actual, "retainAll");

		assertEquals(expected.removeIf(value -> value % 2 == 0), actual.removeIf(value -> value % 2 == 0));
		assertSameElements(expected, actual, "removeIf");
		actual.assertInvariants();
	}

	@InjectedTest
	void equalsAndHashCode_matchHashSet(Contents contents) {
		HashSet<Integer> expected = new HashSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		assertEquals(expected, actual);
		assertEquals(actual, expected);
		assertEquals(expected.hashCode(), actual.hashCode());
	}

	@InjectedTest
	void toString_matchesTreeSet(Contents contents) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		contents.applyTo(expected);
		contents.applyTo(actual);

		assertEquals(expected.toString(), actual.toString());
	}

	@InjectedTest
	void representationFitsShape(Contents contents) {
		IntSet actual = new IntSet();
		contents.applyTo(actual);

		switch (contents) {
			case EMPTY -> {
				assertEquals(0, actual.tailStart());
				assertEquals(0, actual.tailCount());
			}
			case SINGLETON -> {
				assertEquals(0, actual.tailStart());
				assertEquals(1, actual.tailCount());
			}
			case DENSE_LOW -> {
				assertEquals(2, actual.tailStart());
				assertEquals(0, actual.tailCount());
			}
			case DENSE_LARGE -> {
				assertEquals(313, actual.tailStart());
				assertEquals(0, actual.tailCount());
			}
			case SPARSE -> {
				assertEquals(0, actual.tailStart());
				assertEquals(20, actual.tailCount());
			}
			case MIXED -> {
				assertEquals(2, actual.tailStart());
				assertEquals(1, actual.tailCount());
			}
			case BOUNDARIES -> {
				assertEquals(5, actual.tailStart());
				assertEquals(0, actual.tailCount());
			}
			case FULL_RANGE -> {
				assertEquals(0, actual.tailStart());
				assertEquals(4, actual.tailCount());
			}
		}
		actual.assertInvariants();
	}

	@InjectedTest
	void randomOperations_matchTreeSet(Scenario scenario) {
		TreeSet<Integer> expected = new TreeSet<>();
		IntSet actual = new IntSet();
		scenario.initial().applyTo(expected);
		scenario.initial().applyTo(actual);
		actual.assertInvariants();

		Random random = new Random(scenario.seed());
		for (int step = 0; step < scenario.steps(); step++) {
			Operation operation = OPERATIONS[random.nextInt(OPERATIONS.length)];
			int value;
			if ((operation == Operation.REMOVE || operation == Operation.CONTAINS)
				&& !expected.isEmpty() && random.nextBoolean()) {
				value = random.nextBoolean() ? expected.first() : expected.last();
			} else {
				value = scenario.distribution().next(random, scenario.universe());
			}

			Boolean expectedResult = operation.apply(expected, value);
			Boolean actualResult = operation.apply(actual, value);
			String context = scenario.name() + " step " + step + ": " + operation + "(" + value + ")";
			assertEquals(expectedResult, actualResult, context + " must return the same result as TreeSet");
			assertEquals(expected.size(), actual.size(), context + " must have the same size as TreeSet");
			assertEquals(expected.isEmpty(), actual.isEmpty(), context + " must match TreeSet's emptiness");
			assertSameElements(expected, actual, context);
			actual.assertInvariants();
		}
	}

	@Test
	void emptyIterator_throwsNoSuchElementException() {
		assertThrows(NoSuchElementException.class, () -> new IntSet().iterator().next());
	}

	@Test
	void iteratorRemove_withoutNext_throws() {
		IntSet set = new IntSet();
		set.add(1);
		Iterator<Integer> iterator = set.iterator();
		assertThrows(IllegalStateException.class, iterator::remove);
		assertEquals(1, iterator.next());
		iterator.remove();
		assertThrows(IllegalStateException.class, iterator::remove);
		assertFalse(iterator.hasNext());
		assertThrows(NoSuchElementException.class, iterator::next);
	}

	@Test
	void negativeValues_areOutsideTheDomain() {
		IntSet set = new IntSet();
		assertThrows(IllegalArgumentException.class, () -> set.add(-1));
		assertThrows(IllegalArgumentException.class, () -> set.add(Integer.valueOf(-1)));
		assertFalse(set.contains(-1));
		assertFalse(set.remove(-1));
		assertTrue(set.isEmpty());
		set.assertInvariants();
	}

	@Test
	void nullValues_areNotPermitted() {
		IntSet set = new IntSet();
		assertFalse(set.contains(null));
		assertFalse(set.remove(null));
		assertThrows(NullPointerException.class, () -> set.add(null));
	}

	@Test
	void maxValue_staysASparseValue() {
		IntSet set = new IntSet();
		assertTrue(set.add(Integer.MAX_VALUE));
		assertEquals(0, set.tailStart());
		assertEquals(1, set.tailCount());
		assertTrue(set.contains(Integer.MAX_VALUE));
		set.assertInvariants();
	}

	@Test
	void boundary_movesWithTheValues() {
		IntSet set = new IntSet();
		for (int i = 0; i < 64; i++) {
			set.add(i);
		}
		assertEquals(2, set.tailStart());
		assertEquals(0, set.tailCount());
		set.assertInvariants();

		for (int i = 32; i < 64; i++) {
			set.remove(i);
		}
		assertEquals(1, set.tailStart());
		assertEquals(0, set.tailCount());
		assertEquals(32, set.size());
		set.assertInvariants();

		assertTrue(set.add(32));
		assertTrue(set.add(33));
		assertEquals(2, set.tailStart());
		assertEquals(0, set.tailCount());
		set.assertInvariants();

		assertTrue(set.remove(33));
		assertEquals(2, set.tailStart());
		assertTrue(set.remove(32));
		assertEquals(1, set.tailStart());
		set.assertInvariants();

		assertTrue(set.add(Integer.MAX_VALUE));
		assertEquals(1, set.tailStart());
		assertEquals(1, set.tailCount());
		set.assertInvariants();

		for (int i = 0; i < 32; i++) {
			assertTrue(set.remove(i));
		}
		assertEquals(0, set.tailStart());
		assertEquals(1, set.tailCount());
		assertEquals(1, set.size());
		set.assertInvariants();

		assertTrue(set.remove(Integer.MAX_VALUE));
		assertTrue(set.isEmpty());
		assertEquals(0, set.tailStart());
		assertEquals(0, set.tailCount());
		set.assertInvariants();
	}

	@Test
	void growth_absorbsValuesAcrossEmptyWords() {
		IntSet set = new IntSet();
		for (int i = 288; i <= 297; i++) {
			assertTrue(set.add(i));
		}
		assertEquals(0, set.tailStart());
		assertEquals(10, set.tailCount());

		assertTrue(set.add(32));

		assertEquals(10, set.tailStart());
		assertEquals(0, set.tailCount());
		assertEquals(11, set.size());
		assertTrue(set.contains(32));
		assertTrue(set.contains(288));
		assertFalse(set.contains(33));
		set.assertInvariants();
	}

	@Test
	void growth_fillsEmptyWordsInsideThePrefix() {
		IntSet set = new IntSet();
		assertTrue(set.add(32));
		assertTrue(set.add(96));
		for (int i = 288; i <= 296; i++) {
			assertTrue(set.add(i));
		}

		assertEquals(10, set.tailStart());
		assertEquals(0, set.tailCount());
		assertEquals(11, set.size());
		assertTrue(set.contains(32));
		assertTrue(set.contains(96));
		assertTrue(set.contains(296));
		assertFalse(set.contains(64));
		set.assertInvariants();
	}

	@Test
	void removingMostOfADenseSet_sparsifies() {
		IntSet set = new IntSet();
		for (int i = 0; i < 10_000; i++) {
			set.add(i);
		}
		assertEquals(313, set.tailStart());

		for (int i = 1; i < 9_990; i++) {
			assertTrue(set.remove(i));
		}

		assertEquals(0, set.tailStart());
		assertEquals(set.size(), set.tailCount());
		assertEquals(11, set.size());
		assertTrue(set.contains(0));
		assertTrue(set.contains(9_999));
		assertFalse(set.contains(5_000));
		set.assertInvariants();
	}

	@Test
	void iteratorRemoval_defersSparsifying() {
		IntSet set = new IntSet();
		for (int i = 0; i < 10_000; i++) {
			set.add(i);
		}
		assertEquals(313, set.tailStart());

		Iterator<Integer> iterator = set.iterator();
		while (iterator.hasNext()) {
			int value = iterator.next();
			if (value != 0 && value < 9_990) {
				iterator.remove();
			}
		}

		assertEquals(313, set.tailStart(), "Iteration must leave the mostly-empty bitvector intact");
		assertEquals(11, set.size());
		set.assertInvariants();

		assertTrue(set.remove(0));
		assertEquals(0, set.tailStart(), "A direct removal must sparsify the mostly-empty set");
		assertEquals(set.size(), set.tailCount());
		set.assertInvariants();
	}

	@Test
	void iteratorRemoval_movesTheTailWhenTheBitvectorShrinks() {
		IntSet set = new IntSet();
		for (int i = 0; i < 32; i++) {
			set.add(i);
		}
		assertTrue(set.add(100));
		assertEquals(1, set.tailStart());
		assertEquals(1, set.tailCount());

		Iterator<Integer> iterator = set.iterator();
		while (iterator.hasNext()) {
			int value = iterator.next();
			if (value < 32) {
				iterator.remove();
			}
		}

		assertEquals(Set.of(100), set);
		assertEquals(0, set.tailStart());
		assertEquals(1, set.tailCount());
		set.assertInvariants();
	}

	private static void assertSameElements(Set<Integer> expected, IntSet actual, String context) {
		String message = context + " must match TreeSet in ascending order";
		Iterator<Integer> expectedIterator = expected.iterator();
		Iterator<Integer> actualIterator = actual.iterator();
		while (expectedIterator.hasNext()) {
			assertTrue(actualIterator.hasNext(), message);
			assertEquals(expectedIterator.next(), actualIterator.next(), message);
		}
		assertFalse(actualIterator.hasNext(), message);
	}
}
