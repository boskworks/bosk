package works.bosk;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import works.bosk.exceptions.InvalidTypeException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.testing.BoskTestUtils.boskName;

class OptionalRefsTest extends AbstractRoundTripTest {
	private static final Identifier ID = Identifier.from("dummy");

	@Test
	void testReferenceOptionalNotAllowed() {
		Bosk<OptionalString> bosk = new Bosk<>(boskName(), OptionalString.class, _ -> new OptionalString(ID, Optional.empty()), BoskConfig.simple());
		InvalidTypeException e = assertThrows(InvalidTypeException.class, () -> bosk.rootReference().then(Optional.class, Path.just("field")));
		assertThat(e.getMessage(), containsString("not supported"));
	}

	@ParameterizedTest
	@MethodSource("driverFactories")
	void testOptionalString(DriverFactory<OptionalString> driverFactory) throws InvalidTypeException {
		doTest(new OptionalString(ID, Optional.empty()), b->"HERE I AM", driverFactory);
	}

	public record OptionalString(
		Identifier id,
		Optional<String> field
	) implements Entity { }

	@ParameterizedTest
	@MethodSource("driverFactories")
	void testOptionalEntity(DriverFactory<OptionalEntity> driverFactory) throws InvalidTypeException {
		OptionalEntity empty = new OptionalEntity(ID, Optional.empty());
		doTest(empty, b->empty, driverFactory);
	}

	public record OptionalEntity(
		Identifier id,
		Optional<OptionalEntity> field
	) implements Entity { }

	//@ParameterizedTest // TODO: Reference<Reference<?>> is not yet supported
	@MethodSource("driverFactories")
	void testOptionalReference(DriverFactory<OptionalReference> driverFactory) throws InvalidTypeException {
		doTest(new OptionalReference(ID, Optional.empty()), Bosk::rootReference, driverFactory);
	}

	public record OptionalReference(
		Identifier id,
		Optional<Reference<OptionalReference>> field
	) implements Entity { }

	@ParameterizedTest
	@MethodSource("driverFactories")
	void testOptionalCatalog(DriverFactory<OptionalCatalog> driverFactory) throws InvalidTypeException {
		OptionalCatalog empty = new OptionalCatalog(ID, Optional.empty());
		doTest(empty, b->Catalog.of(empty), driverFactory);
	}

	public record OptionalCatalog(
		Identifier id,
		Optional<Catalog<OptionalCatalog>> field
	) implements Entity { }

	@ParameterizedTest
	@MethodSource("driverFactories")
	void testOptionalListing(DriverFactory<OptionalListing> driverFactory) throws InvalidTypeException {
		OptionalListing empty = new OptionalListing(ID, Catalog.empty(), Optional.empty());
		doTest(empty, b->Listing.of(b.rootReference().thenCatalog(OptionalListing.class, "catalog"), ID), driverFactory);
	}

	public record OptionalListing(
		Identifier id,
		Catalog<OptionalListing> catalog,
		Optional<Listing<OptionalListing>> field
	) implements Entity { }

	@ParameterizedTest
	@MethodSource("driverFactories")
	void testOptionalSideTable(DriverFactory<OptionalSideTable> driverFactory) throws InvalidTypeException {
		OptionalSideTable empty = new OptionalSideTable(ID, Catalog.empty(), Optional.empty());
		doTest(empty, b-> SideTable.of(b.rootReference().thenCatalog(OptionalSideTable.class, "catalog"), ID, "Howdy"), driverFactory);
	}

	public record OptionalSideTable(
		Identifier id,
		Catalog<OptionalSideTable> catalog,
		Optional<SideTable<OptionalSideTable, String>> field
	) implements Entity { }

	private interface ValueFactory<R extends Entity, V> {
		V createFrom(Bosk<R> bosk) throws InvalidTypeException;
	}

	private <E extends Entity, V> void doTest(E initialRoot, ValueFactory<E, V> valueFactory, DriverFactory<E> driverFactory) throws InvalidTypeException {
		Bosk<E> bosk = new Bosk<>(
			boskName(),
			initialRoot.getClass(),
			_ -> initialRoot,
			BoskConfig.<E>builder()
				.driverFactory(driverFactory)
				.build()
		);
		V value = valueFactory.createFrom(bosk);
		@SuppressWarnings("unchecked")
		Reference<V> optionalRef = bosk.rootReference().then((Class<V>)value.getClass(), "field");
		try (var _ = bosk.readSession()) {
			assertNull(optionalRef.valueIfExists());
		}
		bosk.driver().submitReplacement(optionalRef, value);
		try (var _ = bosk.readSession()) {
			assertEquals(value, optionalRef.valueIfExists());
		}
		bosk.driver().submitDeletion(optionalRef);
		try (var _ = bosk.readSession()) {
			assertNull(optionalRef.valueIfExists());
		}

		// Try other ways of getting the same reference
		@SuppressWarnings("unchecked")
		Reference<V> ref2 = bosk.rootReference().then((Class<V>) value.getClass(), Path.just("field"));
		assertEquals(optionalRef, ref2);
	}

}
