package works.bosk;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk.DefaultRootFunction;
import works.bosk.TypeValidationTest.BoxedPrimitives;
import works.bosk.TypeValidationTest.SimpleTypes;
import works.bosk.drivers.ForwardingDriver;
import works.bosk.drivers.NoOpDriver;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.BoskTestUtils.boskName;
import static works.bosk.TypeValidationTest.SimpleTypes.MyEnum.LEFT;

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
			_ -> root,
			(_, d) -> {
				driver.set(new ForwardingDriver(d));
				return driver.get();
			},
			Bosk.simpleRegistrar());

		assertEquals(name, bosk.name());
		assertEquals(rootType, bosk.rootReference().targetType());

		// The driver object and root node should be exactly the same object passed in

		assertSame(driver.get(), bosk.getDriver(ForwardingDriver.class));

		try (var _ = bosk.readContext()) {
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
				bosk -> new TypeValidationTest.ArrayField(Identifier.from("test"), new String[0]),
				Bosk.simpleDriver(),
				Bosk.simpleRegistrar()));
	}

	@Test
	void badDriverInitialRoot_throws() {
		assertInitialRootThrows(NullPointerException.class, () -> null);
		assertInitialRootThrows(ClassCastException.class, () -> new TypeValidationTest.CatalogOfInvalidType(Identifier.from("whoops"), Catalog.empty()));
		assertInitialRootThrows(IllegalArgumentException.class, () -> { throw new InvalidTypeException("Whoopsie"); });
		assertInitialRootThrows(IllegalArgumentException.class, () -> { throw new IOException("Whoopsie"); });
		assertInitialRootThrows(IllegalArgumentException.class, () -> { throw new InterruptedException("Whoopsie"); });
	}

	@Test
	void badDefaultRootFunction_throws() {
		assertDefaultRootThrows(NullPointerException.class, _ -> null);
		assertDefaultRootThrows(ClassCastException.class, _ -> new TypeValidationTest.CatalogOfInvalidType(Identifier.from("whoops"), Catalog.empty()));
		assertDefaultRootThrows(IllegalArgumentException.class, _ -> { throw new InvalidTypeException("Whoopsie"); });
	}

	@Test
	void mismatchedRootType_throws() {
		assertThrows(ClassCastException.class, ()->
			new Bosk<Entity>(
				boskName("Mismatched root"),
				BoxedPrimitives.class,
				bosk -> newEntity(),
				Bosk.simpleDriver(),
				Bosk.simpleRegistrar())
		);
	}

	@Test
	void driverInitialRoot_matches() {
		SimpleTypes root = newEntity();
		Bosk<StateTreeNode> bosk = new Bosk<>(
			boskName(),
			SimpleTypes.class,
			_ -> { throw new AssertionError("Shouldn't be called"); },
			initialRootDriver(() -> root),
			Bosk.simpleRegistrar());
		try (var _ = bosk.readContext()) {
			assertSame(root, bosk.rootReference().value());
		}
	}

	@Test
	void defaultRoot_matches() {
		SimpleTypes root = newEntity();
		Bosk<StateTreeNode> valueBosk = Bosk.simple(boskName(), root);
		try (var _ = valueBosk.readContext()) {
			assertSame(root, valueBosk.rootReference().value());
		}
	}

	//
	//  Helpers
	//

	/**
	 * The "initial root" is the one returned from the driver.
	 */
	private static void assertInitialRootThrows(Class<? extends Throwable> expectedType, InitialRootFunction initialRootFunction) {
		assertThrows(expectedType, () -> new Bosk<>(
			boskName(),
			SimpleTypes.class,
			_ -> newEntity(),
			initialRootDriver(initialRootFunction),
			Bosk.simpleRegistrar()));
	}

	/**
	 * The "default root" is the one passed to the bosk constructor.
	 */
	private static void assertDefaultRootThrows(Class<? extends Throwable> expectedType, DefaultRootFunction<StateTreeNode> defaultRootFunction) {
		assertThrows(expectedType, () -> new Bosk<>(
			boskName(),
			SimpleTypes.class,
			defaultRootFunction,
			Bosk.simpleDriver(),
			Bosk.simpleRegistrar()));
	}

	@NotNull
	private static DriverFactory<StateTreeNode> initialRootDriver(InitialRootFunction initialRootFunction) {
		return (_, _) -> new NoOpDriver() {
			@Override
			public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
				return initialRootFunction.get();
			}
		};
	}

	interface InitialRootFunction {
		StateTreeNode get() throws InvalidTypeException, IOException, InterruptedException;
	}

	private static SimpleTypes newEntity() {
		return new SimpleTypes(Identifier.unique("test"), "string", LEFT);
	}

}
