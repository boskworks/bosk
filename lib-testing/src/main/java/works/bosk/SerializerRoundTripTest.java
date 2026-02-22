package works.bosk;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SerializerRoundTripTest extends AbstractRoundTripTest {
	@ParameterizedTest
	@MethodSource("driverFactories")
	void testRoundTrip(DriverFactory<TestRoot> driverFactory) throws InvalidTypeException {
		Bosk<TestRoot> bosk = setUpBosk(driverFactory);
		TestRoot originalRoot;
		try (var _ = bosk.readSession()) {
			originalRoot = bosk.rootReference().value();
		}
		bosk.driver().submitReplacement(bosk.rootReference(), originalRoot);

		try (var _ = bosk.readSession()) {
			// Use our entity's equals() to check that all is well
			//
			assertEquals(originalRoot, bosk.rootReference().value());

			// Ensure enclosing references point to the right entities
			//
			Reference<TestEntity> parentRef = bosk.rootReference().then(TestEntity.class, "entities", "parent");
			assertEquals(parentRef.then(ImplicitRefs.class, "implicitRefs"), parentRef.value().implicitRefs().reference());
			assertEquals(parentRef, parentRef.value().implicitRefs().enclosingRef());
		}
	}

}
