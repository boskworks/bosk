package works.bosk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentAppendOnlyListTest {
	final ConcurrentAppendOnlyList<String> list = new ConcurrentAppendOnlyList<>();

	@Test
	void newListIsEmpty() {
		assertEquals(0, list.size());
		assertTrue(list.isEmpty());
		assertFalse(list.iterator().hasNext());
		assertEquals(List.of(), List.copyOf(list));
		assertEquals(0, list.toArray().length);
	}

	@Test
	void elementsComeBackInAppendOrder() {
		list.add("a");
		list.add("b");
		list.add("c");
		assertEquals(3, list.size());
		assertEquals("a", list.get(0));
		assertEquals("b", list.get(1));
		assertEquals("c", list.get(2));
		assertEquals(List.of("a", "b", "c"), List.copyOf(list));
	}

	@Test
	void getRejectsIndexesOutsideTheList() {
		list.add("a");
		assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
	}

	@Test
	void nullsAreRejected() {
		assertThrows(NullPointerException.class, () -> list.add(null));
		assertThrows(NullPointerException.class, () -> list.addAll(Collections.singletonList(null)));
		assertTrue(list.isEmpty(), "A rejected element must not be recorded");
	}

	@Test
	void addAtIndexIsUnsupportedEvenForTheEnd() {
		list.add("a");
		assertThrows(UnsupportedOperationException.class, () -> list.add(0, "b"));
		assertThrows(UnsupportedOperationException.class, () -> list.add(1, "b"));
		assertThrows(UnsupportedOperationException.class, () -> list.add(5, "b"));
		assertThrows(UnsupportedOperationException.class, () -> list.add(-1, "b"));
		assertEquals(List.of("a"), List.copyOf(list));
	}

	@Test
	void addAllAppendsEverything() {
		assertTrue(list.addAll(List.of("a", "b", "c")));
		assertFalse(list.addAll(List.of()));
		assertEquals(List.of("a", "b", "c"), List.copyOf(list));
	}

	@Test
	void mutatorsAreUnsupported() {
		list.addAll(List.of("a", "b", "c"));
		assertThrows(UnsupportedOperationException.class, () -> list.set(0, "x"));
		assertThrows(UnsupportedOperationException.class, () -> list.remove(0));
		assertThrows(UnsupportedOperationException.class, () -> list.remove("a"));
		assertThrows(UnsupportedOperationException.class, () -> list.removeAll(List.of("a")));
		assertThrows(UnsupportedOperationException.class, () -> list.retainAll(List.of("a")));
		assertThrows(UnsupportedOperationException.class, () -> list.removeIf(s -> true));
		assertThrows(UnsupportedOperationException.class, () -> list.replaceAll(String::toUpperCase));
		assertThrows(UnsupportedOperationException.class, () -> list.sort(String::compareTo));
		assertThrows(UnsupportedOperationException.class, list::clear);
		assertThrows(UnsupportedOperationException.class, () -> list.iterator().remove());
		assertEquals(List.of("a", "b", "c"), List.copyOf(list), "Nothing should have changed");
	}

	@Test
	void listIteratorMutatorsAreUnsupported() {
		list.addAll(List.of("a", "b"));
		ListIterator<String> iterator = list.listIterator();
		iterator.next();
		assertThrows(UnsupportedOperationException.class, iterator::remove);
		assertThrows(UnsupportedOperationException.class, () -> iterator.set("x"));
		assertThrows(UnsupportedOperationException.class, () -> iterator.add("x"));
	}

	@Test
	void listIteratorGoesBothWays() {
		list.addAll(List.of("a", "b", "c"));
		ListIterator<String> iterator = list.listIterator();
		assertEquals(0, iterator.nextIndex());
		assertEquals(-1, iterator.previousIndex());
		assertEquals("a", iterator.next());
		assertEquals("b", iterator.next());
		assertEquals(2, iterator.nextIndex());
		assertEquals(1, iterator.previousIndex());
		assertEquals("b", iterator.previous(), "previous steps back to the element just passed");
		assertEquals("b", iterator.next(), "next after previous returns the same element");
		assertEquals("c", iterator.next());
		assertFalse(iterator.hasNext());
		assertThrows(NoSuchElementException.class, iterator::next);
		assertEquals("c", iterator.previous());
		assertEquals("b", iterator.previous());
		assertEquals("a", iterator.previous());
		assertThrows(NoSuchElementException.class, iterator::previous);
	}

	@Test
	void iteratorSeesTheListAsItWasWhenCreated() {
		list.addAll(List.of("a", "b"));
		Iterator<String> iterator = list.iterator();
		list.add("c");

		List<String> seen = new ArrayList<>();
		iterator.forEachRemaining(seen::add);
		assertEquals(List.of("a", "b"), seen, "An iterator must not see later appends");
		assertEquals(List.of("a", "b", "c"), List.copyOf(list));
	}

	@Test
	void listIteratorSeesTheListAsItWasWhenCreated() {
		list.addAll(List.of("a", "b"));
		ListIterator<String> iterator = list.listIterator();
		list.add("c");
		assertEquals(List.of("a", "b"), collect(iterator));
	}

	@Test
	void spliteratorSeesTheListAsItWasWhenCreated() {
		list.addAll(List.of("a", "b"));
		Spliterator<String> spliterator = list.spliterator();
		list.add("c");
		assertEquals(List.of("a", "b"), collect(spliterator));
	}

	@Test
	void spliteratorReportsWhatItCanDo() {
		list.addAll(List.of("a", "b", "c"));
		Spliterator<String> spliterator = list.spliterator();
		assertTrue(spliterator.hasCharacteristics(Spliterator.ORDERED));
		assertTrue(spliterator.hasCharacteristics(Spliterator.SIZED));
		assertTrue(spliterator.hasCharacteristics(Spliterator.SUBSIZED));
		assertTrue(spliterator.hasCharacteristics(Spliterator.IMMUTABLE));
		assertTrue(spliterator.hasCharacteristics(Spliterator.NONNULL));
		assertEquals(3, spliterator.estimateSize());
	}

	@Test
	void spliteratorSplitsCoverEveryElementOnce() {
		List<String> expected = IntStream.range(0, 1000).mapToObj(i -> "e" + i).collect(Collectors.toList());
		list.addAll(expected);

		List<Spliterator<String>> pieces = new ArrayList<>();
		Spliterator<String> remaining = list.spliterator();
		Spliterator<String> prefix;
		while ((prefix = remaining.trySplit()) != null) {
			assertTrue(prefix.estimateSize() > 0, "A split must not be empty");
			pieces.add(prefix);
		}
		pieces.add(remaining);

		List<String> elements = pieces.stream()
			.flatMap(piece -> StreamSupport.stream(piece, false))
			.collect(Collectors.toList());
		assertEquals(expected, elements);
	}

	@Test
	void streamsSeeTheSnapshot() {
		list.addAll(List.of("a", "b", "c"));
		assertEquals(3, list.stream().count());
		assertEquals("abc", list.stream().collect(Collectors.joining()));
		assertEquals(List.of("a", "b", "c"), list.stream().collect(Collectors.toList()));
	}

	@Test
	void parallelStreamSeesTheSnapshot() {
		List<Integer> numbers = IntStream.range(0, 10_000).boxed().collect(Collectors.toList());
		ConcurrentAppendOnlyList<Integer> integers = new ConcurrentAppendOnlyList<>(numbers);
		assertEquals(numbers.size(), integers.parallelStream().count());
		assertEquals(numbers.stream().mapToLong(Integer::longValue).sum(),
			integers.parallelStream().mapToLong(Integer::longValue).sum());
	}

	@Test
	void toArrayCopiesTheElements() {
		list.addAll(List.of("a", "b"));
		assertEquals(List.of("a", "b"), List.of(list.toArray()));

		Object[] into = new Object[4];
		Object[] returned = list.toArray(into);
		assertSame(into, returned);
		assertEquals("a", into[0]);
		assertEquals("b", into[1]);
		assertNull(into[2], "The element after the end must be null");
	}

	@Test
	void toArrayOfTheRightTypeWorks() {
		list.addAll(List.of("a", "b"));
		String[] small = list.toArray(new String[0]);
		assertEquals(2, small.length);
		assertEquals("a", small[0]);
		assertEquals("b", small[1]);

		String[] exact = new String[2];
		assertSame(exact, list.toArray(exact));

		String[] tooBig = new String[4];
		list.toArray(tooBig);
		assertNull(tooBig[2]);
	}

	@Test
	void toArrayWithTheWrongElementTypeThrows() {
		list.add("a");
		assertThrows(ArrayStoreException.class, () -> list.toArray(new Integer[1]));
	}

	@Test
	void growsAcrossTheInitialCapacity() {
		ConcurrentAppendOnlyList<Integer> integers = new ConcurrentAppendOnlyList<>();
		for (int i = 0; i < 100; i++) {
			integers.add(i);
		}
		assertEquals(100, integers.size());
		for (int i = 0; i < 100; i++) {
			assertEquals(i, integers.get(i));
		}
	}

	@Test
	void subListOfAPrefixIsASnapshot() {
		list.addAll(List.of("a", "b", "c"));
		List<String> prefix = list.subList(0, 2);
		list.add("d");

		assertEquals(List.of("a", "b"), List.copyOf(prefix), "A sublist must not see later appends");
		assertEquals(2, prefix.size());
		assertEquals(List.of("a", "b", "c", "d"), List.copyOf(list));
	}

	@Test
	void subListRefusesAppends() {
		list.addAll(List.of("a", "b"));
		List<String> prefix = list.subList(0, 2);

		assertThrows(UnsupportedOperationException.class, () -> prefix.add("c"));
		assertThrows(UnsupportedOperationException.class, () -> prefix.addAll(List.of("c")));
		assertEquals(List.of("a", "b"), List.copyOf(list), "A view must not touch its parent");
	}

	@Test
	void subListOnlySupportsPrefixes() {
		list.addAll(List.of("a", "b", "c"));

		assertEquals(List.of(), List.copyOf(list.subList(0, 0)));
		assertEquals(List.of("a", "b", "c"), List.copyOf(list.subList(0, 3)));
		assertThrows(UnsupportedOperationException.class, () -> list.subList(1, 3));
		assertThrows(IndexOutOfBoundsException.class, () -> list.subList(0, 4));
		assertThrows(IndexOutOfBoundsException.class, () -> list.subList(-1, 1));
		assertThrows(IndexOutOfBoundsException.class, () -> list.subList(2, 1));
	}

	@Test
	void listEqualityFollowsListSemantics() {
		list.addAll(List.of("a", "b"));
		assertEquals(List.of("a", "b"), list);
		assertEquals(List.of("a", "b").hashCode(), list.hashCode());
		assertEquals("[a, b]", list.toString());
		assertTrue(list.contains("b"));
		assertEquals(1, list.indexOf("b"));
		assertEquals(1, list.lastIndexOf("b"));
		assertFalse(list.contains("z"));
	}

	@Test
	void getIsStableForAGivenIndex() {
		list.addAll(List.of("a", "b", "c"));
		String first = list.get(1);
		list.add("d");
		assertSame(first, list.get(1), "Appending must not move existing elements");
	}

	@Test
	void concurrentAppendsAllArrive() {
		assertTimeoutPreemptively(ofSeconds(30), () -> {
			int threads = 8;
			int perThread = 500;
			ConcurrentAppendOnlyList<Integer> integers = new ConcurrentAppendOnlyList<>();
			runConcurrently(threads, thread -> {
				for (int i = 0; i < perThread; i++) {
					integers.add(thread * perThread + i);
				}
			});

			assertEquals(threads * perThread, integers.size());
			assertEquals(threads * perThread, List.copyOf(integers).size(), "Every element must be present");
			assertEquals(threads * perThread, List.copyOf(integers).stream().distinct().count(),
				"No element may be lost or duplicated");
		});
	}

	@Test
	void readersNeverSeeAHoleOrAnException() {
		assertTimeoutPreemptively(ofSeconds(30), () -> {
			ConcurrentAppendOnlyList<Integer> integers = new ConcurrentAppendOnlyList<>();
			int writerCount = 4;
			int perWriter = 2_000;
			CountDownLatch done = new CountDownLatch(writerCount);
			AtomicReference<Throwable> failure = new AtomicReference<>();

			ExecutorService writers = Executors.newFixedThreadPool(writerCount);
			try {
				for (int w = 0; w < writerCount; w++) {
					int writer = w;
					writers.submit(() -> {
						try {
							for (int i = 0; i < perWriter; i++) {
								integers.add(writer * perWriter + i);
							}
						} catch (Throwable t) {
							failure.compareAndSet(null, t);
						} finally {
							done.countDown();
						}
					});
				}
				while (done.getCount() > 0) {
					int limit = integers.size();
					for (int i = 0; i < limit; i++) {
						assertNotNull(integers.get(i), "Index " + i + " was below the size but unset");
					}
					for (Integer element : integers) {
						assertNotNull(element, "An iterator saw an unset element");
					}
				}
				assertTrue(done.await(30, TimeUnit.SECONDS));
			} finally {
				writers.shutdownNow();
			}
			assertNull(failure.get());
			assertEquals(writerCount * perWriter, integers.size());
		});
	}
	private void runConcurrently(int threads, java.util.function.IntConsumer body) throws InterruptedException {
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			for (int t = 0; t < threads; t++) {
				int thread = t;
				executor.submit(() -> {
					try {
						start.await();
						body.accept(thread);
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				});
			}
			start.countDown();
			executor.shutdown();
			assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Workers did not finish");
		} finally {
			executor.shutdownNow();
		}
		if (failure.get() != null) {
			throw new AssertionError("Worker failed", failure.get());
		}
	}

	private static <E> List<E> collect(Iterator<E> iterator) {
		List<E> result = new ArrayList<>();
		iterator.forEachRemaining(result::add);
		return result;
	}

	private static <E> List<E> collect(Spliterator<E> spliterator) {
		List<E> result = new ArrayList<>();
		spliterator.forEachRemaining(result::add);
		return result;
	}
}
