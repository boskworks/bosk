package works.bosk;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk.DefaultStateFunction;
import works.bosk.BoskDriver.InitialState;
import works.bosk.TypeValidationTest.BoxedPrimitives;
import works.bosk.TypeValidationTest.SimpleTypes;
import works.bosk.drivers.ForwardingDriver;
import works.bosk.drivers.NoOpDriver;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.TypeValidationTest.SimpleTypes.MyEnum.LEFT;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * These tests don't use @{@link org.junit.jupiter.api.BeforeEach}
 * to pre-create a {@link Bosk} because we want to test the constructor itself.
 */
public class BoskConstructorTest {

	@Test
	void basicProperties_correctValues() {
		String name = boskName();
		Type rootType = SimpleTypes.class;
		StateTreeNode root = newEntity();

		AtomicReference<BoskDriver> driver = new AtomicReference<>();
		Bosk<StateTreeNode> bosk = new Bosk<>(
			name,
			rootType,
			_ -> InitialState.of(root),
			BoskConfig.builder().driverFactory((_, d) -> {
				driver.set(new ForwardingDriver(d));
				return driver.get();
			}).build());

		assertEquals(name, bosk.name());
		assertEquals(rootType, bosk.rootReference().targetType());

		// The driver object and root node should be exactly the same object passed in

		assertSame(driver.get(), bosk.getDriver(ForwardingDriver.class));

		try (var _ = bosk.readSession()) {
			assertSame(root, bosk.rootReference().value());
		}
	}

	/**
	 * Not a thorough test of type validation. Just testing that invalid types are rejected.
	 *
	 * @see TypeValidationTest
	 */
	@Test
	void invalidRootType_throws() {
		assertThrows(IllegalArgumentException.class, ()->
			new Bosk<TypeValidationTest.ArrayField>(
				boskName("Invalid root type"),
				TypeValidationTest.ArrayField.class,
				_ -> InitialState.of(new TypeValidationTest.ArrayField(Identifier.from("test"), new String[0])),
				BoskConfig.simple()));
	}

	@Test
	void badDriverInitialRoot_throws() {
		assertInitialRootThrows(NullPointerException.class, () -> null);
		assertInitialRootThrows(NullPointerException.class, () -> InitialState.of(null));
		assertInitialRootThrows(IllegalArgumentException.class, () -> { throw new InvalidTypeException("Whoopsie"); });
		assertInitialRootThrows(IllegalArgumentException.class, () -> { throw new IOException("Whoopsie"); });
		assertInitialRootThrows(IllegalArgumentException.class, () -> { throw new InterruptedException("Whoopsie"); });
	}

	@Test
	void badDefaultRootFunction_throws() {
		assertDefaultRootThrows(NullPointerException.class, _ -> null);
		assertDefaultRootThrows(NullPointerException.class, _ -> InitialState.of(null));
		assertDefaultRootThrows(ClassCastException.class, _ -> InitialState.of(new TypeValidationTest.CatalogOfInvalidType(Identifier.from("whoops"), Catalog.empty())));
		assertDefaultRootThrows(IllegalArgumentException.class, _ -> { throw new InvalidTypeException("Whoopsie"); });
	}

	@Test
	void mismatchedRootType_throws() {
		assertThrows(ClassCastException.class, ()->
			new Bosk<Entity>(
				boskName("Mismatched root"),
				BoxedPrimitives.class,
				_ -> InitialState.of(newEntity()),
				BoskConfig.simple())
		);
	}

	@Test
	void driverInitialRoot_matches() {
		SimpleTypes root = newEntity();
		Bosk<SimpleTypes> bosk = new Bosk<>(
			boskName(),
			SimpleTypes.class,
			_ -> { throw new AssertionError("Shouldn't be called"); },
			BoskConfig.<SimpleTypes>builder().driverFactory(initialStateDriver(() -> InitialState.of(root))).build());
		try (var _ = bosk.readSession()) {
			assertSame(root, bosk.rootReference().value());
		}
	}

	@Test
	void defaultRoot_matches() {
		SimpleTypes root = newEntity();
		Bosk<StateTreeNode> valueBosk = Bosk.simple(boskName(), root);
		try (var _ = valueBosk.readSession()) {
			assertSame(root, valueBosk.rootReference().value());
		}
	}

	//
	//  Helpers
	//

	/**
	 * The "initial root" is the one returned from the driver.
	 */
	private static void assertInitialRootThrows(Class<? extends Throwable> expectedType, InitialStateFunction<SimpleTypes> initialStateFunction) {
		assertThrows(expectedType, () -> new Bosk<>(
			boskName(),
			SimpleTypes.class,
			_ -> InitialState.of(newEntity()),
			BoskConfig.<SimpleTypes>builder()
				.driverFactory(initialStateDriver(initialStateFunction))
				.build()
		));
	}

	/**
	 * The "default root" is the one passed to the bosk constructor.
	 */
	private static void assertDefaultRootThrows(Class<? extends Throwable> expectedType, DefaultStateFunction<StateTreeNode> defaultStateFunction) {
		assertThrows(expectedType, () -> new Bosk<>(
			boskName(),
			SimpleTypes.class,
			defaultStateFunction,
			BoskConfig.simple()));
	}

	@NotNull
	private static <R extends StateTreeNode> DriverFactory<R> initialStateDriver(InitialStateFunction<R> initialStateFunction) {
		return (_, _) -> new NoOpDriver() {
			@Override
			public <RR extends StateTreeNode> InitialState<RR> initialState(Class<RR> rootType) throws InvalidTypeException, IOException, InterruptedException {
				return initialStateFunction.get().cast(rootType);
			}
		};
	}

	interface InitialStateFunction<R extends StateTreeNode> {
		InitialState<R> get() throws InvalidTypeException, IOException, InterruptedException;
	}

	private static SimpleTypes newEntity() {
		return new SimpleTypes(Identifier.unique("test"), "string", LEFT);
	}

}
