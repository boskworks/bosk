package works.bosk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldNameConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NonexistentReferenceException;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.BoskTestUtils.boskName;

/*
 * TODO: This test is written in a mighty weird style. Change it to set up
 * the bosk in a more normal manner, and try to use buildReferences too.
 */
class ListingTest {

	static Stream<Arguments> provideListingArguments() {
		return idStreams()
			.map(Stream::distinct)
			.map(stream -> stream.map(id -> new TestEntity(Identifier.from(id), Catalog.empty()))
				.collect(toList()))
			.map(children -> {
				TestEntity root = new TestEntity(Identifier.unique("parent"), Catalog.of(children));
				Bosk<TestEntity> bosk = new Bosk<>(boskName(), TestEntity.class, _ -> root, Bosk.simpleDriver(), Bosk.simpleRegistrar());
				CatalogReference<TestEntity> catalog;
				try {
					catalog = bosk.rootReference().thenCatalog(TestEntity.class, Path.just(TestEntity.Fields.children));
				} catch (InvalidTypeException e) {
					throw new AssertionError(e);
				}
				Listing<TestEntity> listing = Listing.of(catalog, children.stream().map(TestEntity::id));
				return Arguments.of(listing, children, bosk);
			});
	}

	static Stream<Arguments> provideIDListArguments() throws InvalidTypeException {
		TestEntity child = new TestEntity(Identifier.unique("child"), Catalog.empty());
		List<TestEntity> children = singletonList(child);
		TestEntity root = new TestEntity(Identifier.unique("parent"), Catalog.of(children));
		Bosk<TestEntity> bosk = new Bosk<>(boskName(), TestEntity.class, _ -> root, Bosk.simpleDriver(), Bosk.simpleRegistrar());
		CatalogReference<TestEntity> childrenRef = bosk.rootReference().thenCatalog(TestEntity.class, Path.just(TestEntity.Fields.children));
		return idStreams().map(list -> Arguments.of(list.map(Identifier::from).collect(toList()), childrenRef, bosk));
	}

	public static Stream<Stream<String>> idStreams() {
		return Stream.of(
				Stream.of(),
				Stream.of("a"),
				Stream.of("a", "b", "c"),
				Stream.of("a", "a", "a"),
				Stream.of("a", "b", "b", "a"),
				LongStream.range(1, 100).mapToObj(Long::toString),

				// Hash collisions
				Stream.of("Aa", "BB"),
				Stream.of("Aa", "BB", "Aa")
		);
	}

	@FieldNameConstants
	public record TestEntity(
		Identifier id,
		@EqualsAndHashCode.Exclude Catalog<TestEntity> children
	) implements Entity { }

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testGet(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) throws InvalidTypeException {
		try (var _ = bosk.readContext()) {
			for (TestEntity child: children) {
				TestEntity actual = listing.getValue(child.id());
				assertSame(child, actual, "All expected entities should be present in the Listing");
				Reference<TestEntity> childRef = listing.domain().then(TestEntity.class, child.id().toString());
				assertTrue(listing.contains(childRef));
				TestEntity expected = childRef.value();
				assertSame(expected, actual, "The definition of Listing.get should hold");
			}
			Identifier nonexistent = Identifier.unique("nonexistent");
			assertNull(listing.getValue(nonexistent), "Identifier missing from listing returns null");
			assertFalse(listing.contains(listing.domain().then(TestEntity.class, nonexistent.toString())));
			Listing<TestEntity> danglingRef = listing.withID(nonexistent);
			Assertions.assertThrows(NonexistentReferenceException.class, () -> danglingRef.getValue(nonexistent), "Identifier missing from catalog throws");
		}
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testValueIterator(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Iterator<TestEntity> expected = children.iterator();
		Iterator<TestEntity> actual;
		try (var _ = bosk.readContext()) {
			// ReadContext is needed only when creating the iterator
			actual = listing.valueIterator();
		}
		assertEquals(expected.hasNext(), actual.hasNext());
		while (expected.hasNext()) {
			assertSame(expected.next(), actual.next());
			assertEquals(expected.hasNext(), actual.hasNext());
		}
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testIterator(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Iterator<Reference<TestEntity>> expected = children.stream()
			.map(TestEntity::id)
			.map(listing.domain()::then)
			.iterator();
		Iterator<Reference<TestEntity>> actual = listing.iterator();
		assertEquals(expected.hasNext(), actual.hasNext());
		while (expected.hasNext()) {
			assertEquals(expected.next(), actual.next());
			assertEquals(expected.hasNext(), actual.hasNext());
		}
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testIdStream(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		Iterator<Identifier> expected = children.stream().map(TestEntity::id).iterator();
		listing.idStream().forEachOrdered(actual -> assertSame(expected.next(), actual));
		assertFalse(expected.hasNext(), "No extra elements");
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testStream(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Iterator<TestEntity> expected = children.iterator();
		Stream<TestEntity> stream;
		try (var _ = bosk.readContext()) {
			// ReadContext is needed only when creating the stream
			stream = listing.valueStream();
		}
		stream.forEachOrdered(actual -> assertSame(expected.next(), actual));
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testAsCollection(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Collection<TestEntity> actual;
		try (var _ = bosk.readContext()) {
			// ReadContext is needed only when creating the collection
			actual = listing.valueList();
		}
		assertSameElements(children, actual);
	}

	private <T> void assertSameElements(Collection<T> expected, Collection<T> actual) {
		Iterator<T> eIter = expected.iterator();
		Iterator<T> aIter = actual.iterator();
		assertEquals(eIter.hasNext(), aIter.hasNext());
		while (eIter.hasNext()) {
			assertSame(eIter.next(), aIter.next()); // Stronger than assertEquals
			assertEquals(eIter.hasNext(), aIter.hasNext());
		}
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testSpliterator(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		HashMap <Identifier, Integer> countMap = new HashMap<>();
		HashMap <Identifier, Integer> goodMap = new HashMap<>();

		for (Identifier i : listing.ids()) {
			goodMap.put(i, goodMap.getOrDefault(i, -1)+1);
		}

		Spliterator<TestEntity> newSplit;
		try (var _ = bosk.readContext()) {
			newSplit = listing.values().spliterator();
		}

		newSplit.forEachRemaining(e -> countMap.put(e.id , countMap.getOrDefault(e.id(), -1) + 1));

		Spliterator<TestEntity> splitted = newSplit.trySplit();
		if (splitted != null) {
			splitted.forEachRemaining(e -> countMap.put(e.id , countMap.getOrDefault(e.id(), -1)+1));
		}

		assertTrue((newSplit.characteristics() & Spliterator.IMMUTABLE) != 0);
		assertEquals(goodMap, countMap);
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testSize(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		assertEquals(distinctEntities(children).size(), listing.size());
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testIsEmpty(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		assertEquals(children.isEmpty(), listing.isEmpty());
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testIds(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		assertEquals(distinctEntityIDs(children), listing.ids());
	}

	@Test
	void testEmpty() throws InvalidTypeException {
		TestEntity child = new TestEntity(Identifier.unique("child"), Catalog.empty());
		List<TestEntity> children = singletonList(child);
		TestEntity root = new TestEntity(Identifier.unique("parent"), Catalog.of(children));
		Bosk<TestEntity> bosk = new Bosk<>(boskName(), TestEntity.class, _ -> root, Bosk.simpleDriver(), Bosk.simpleRegistrar());
		CatalogReference<TestEntity> childrenRef = bosk.rootReference().thenCatalog(TestEntity.class, Path.just(TestEntity.Fields.children));

		Listing<TestEntity> actual = Listing.empty(childrenRef);
		assertTrue(actual.isEmpty());
		assertEquals(0, actual.size());

		Iterator<TestEntity> iterator;
		try (var _ = bosk.readContext()) {
			// iterator() needs a ReadContext at creation time
			iterator = actual.valueIterator();
		}
		assertFalse(iterator.hasNext());
	}

	@ParameterizedTest
	@MethodSource("provideIDListArguments")
	void testOfReferenceOfCatalogOfTTIdentifierArray(List<Identifier> ids, CatalogReference<TestEntity> childrenRef, Bosk<TestEntity> bosk) {
		// No ReadContext required
		Listing<TestEntity> actual = Listing.of(childrenRef, ids.toArray(new Identifier[0]));
		assertSameElements(distinctIDs(ids), actual.ids());
	}

	@ParameterizedTest
	@MethodSource("provideIDListArguments")
	void testOfReferenceOfCatalogOfTTCollectionOfIdentifier(List<Identifier> ids, CatalogReference<TestEntity> childrenRef, Bosk<TestEntity> bosk) {
		// No ReadContext required
		Listing<TestEntity> actual = Listing.of(childrenRef, ids);
		assertSameElements(distinctIDs(ids), actual.ids());
	}

	@ParameterizedTest
	@MethodSource("provideIDListArguments")
	void testOfReferenceOfCatalogOfTTStreamOfIdentifier(List<Identifier> ids, CatalogReference<TestEntity> childrenRef, Bosk<TestEntity> bosk) {
		// No ReadContext required
		Listing<TestEntity> actual = Listing.of(childrenRef, ids.stream());
		assertSameElements(distinctIDs(ids), actual.ids());
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testWithID(Listing<TestEntity> originalListing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		// Should match behaviour of LinkedHashSet
		LinkedHashSet<Identifier> exemplar = new LinkedHashSet<>(children.size());
		children.forEach(child -> exemplar.add(child.id()));
		for (TestEntity child: children) {
			Identifier id = child.id();
			Listing<TestEntity> actual = originalListing.withID(id);
			exemplar.add(id);
			assertEquals(exemplar, actual.ids());
			assertEquals(originalListing, actual, "Re-adding existing children makes no difference");
		}

		Identifier newID = Identifier.unique("nonexistent");
		Listing<TestEntity> after = originalListing.withID(newID);
		exemplar.add(newID);
		assertEquals(exemplar, after.ids());

		for (TestEntity child: children) {
			Listing<TestEntity> actual = after.withID(child.id());
			assertEquals(after, actual, "Re-adding existing children makes no difference");
		}
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testWithEntity(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		// Should match behaviour of testWithIdentifier, and therefore (transitively) LinkedHashMap
		Listing<TestEntity> expected = listing;
		Listing<TestEntity> actual = listing;
		for (TestEntity child: children) {
			expected = expected.withID(child.id());
			actual = actual.withEntity(child);
			assertEquals(expected, actual, "Re-adding existing children makes no difference");
		}

		TestEntity newEntity = new TestEntity(Identifier.unique("nonexistent"), Catalog.empty());
		expected = expected.withID(newEntity.id());
		actual = actual.withEntity(newEntity);
		assertEquals(expected, actual);

		for (TestEntity child: children) {
			expected = expected.withID(child.id());
			actual = actual.withEntity(child);
			assertEquals(expected, actual);
		}
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testWithAllIDs(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Identifier id1 = Identifier.unique("nonexistent");
		Identifier id2 = Identifier.unique("nonexistent2");
		Listing<TestEntity> actual = listing.withAllIDs(Stream.of(id1, id2));
		Listing<TestEntity> expected = listing;

		expected = expected.withID(id1).withID(id2);
		assertEquals(expected, actual);

	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testWithoutEntity(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		TestEntity newEntity = new TestEntity(Identifier.unique("existent"), Catalog.empty());
		TestEntity nonexistent = new TestEntity(Identifier.unique("nonexistent"), Catalog.empty());

		Listing<TestEntity> actual = listing.withEntity(newEntity);
		assertNotEquals(actual, listing);
		actual = actual.withoutEntity(newEntity);
		assertEquals(listing, actual);
		assertEquals(listing, actual.withoutEntity(nonexistent));

	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testWithoutID(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Identifier unique = Identifier.unique("existent");
		Identifier nonexistent = Identifier.unique("nonexistent");

		TestEntity newEntity = new TestEntity(unique, Catalog.empty());
		Listing<TestEntity> actual = listing.withEntity(newEntity);
		assertNotEquals(actual, listing);
		actual = actual.withoutID(unique);
		assertEquals(listing, actual);
		assertEquals(listing, actual.withoutID(nonexistent));
	}

	@ParameterizedTest
	@MethodSource("provideListingArguments")
	void testScope(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) throws InvalidTypeException {
		Reference<Catalog<TestEntity>> expected = bosk.rootReference().thenCatalog(TestEntity.class, Path.just(TestEntity.Fields.children));
		assertEquals(expected, listing.domain());
	}

	private List<TestEntity> distinctEntities(List<TestEntity> children) {
		List<TestEntity> result = new ArrayList<>(children.size());
		HashSet<Identifier> added = new HashSet<>(children.size());
		for (TestEntity child: children) {
			if (added.add(child.id())) {
				result.add(child);
			}
		}
		return result;
	}

	private Set<Identifier> distinctEntityIDs(List<TestEntity> children) {
		return children.stream().map(TestEntity::id).collect(toCollection(LinkedHashSet::new));
	}

	private List<Identifier> distinctIDs(List<Identifier> ids) {
		return ids.stream().distinct().collect(toList());
	}

}
