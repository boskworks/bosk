package works.bosk;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.Hook;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.testing.BoskTestUtils.boskName;

class HookScannerTest {
	Bosk<State> bosk;
	Refs refs;

	public record State(String string, int integer) implements StateTreeNode { }
	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
		@ReferencePath("/integer") Reference<Integer> integer();
	}

	@BeforeEach
	void init() throws InvalidTypeException {
		bosk = new Bosk<>(boskName(), State.class, _ -> new State("test", 123), BoskConfig.simple());
		refs = bosk.buildReferences(Refs.class);
	}

	@Test
	void basicFunctionality_works() throws InvalidTypeException {
		List<String> strings = new ArrayList<>();
		List<Integer> integers = new ArrayList<>();
		class Hooks {
			@Hook("/string") void stringChanged(Reference<String> ref) {
				strings.add(ref.value());
			}

			@Hook("/integer") void integerChanged(Reference<Integer> ref) {
				integers.add(ref.value());
			}
		}
		HookScanner.registerHooks(new Hooks(), bosk.rootReference(), bosk.hookRegistrar(), MethodHandles.lookup());

		assertEquals(List.of("test"), strings);
		assertEquals(List.of(123), integers);

		bosk.driver().submitReplacement(bosk.rootReference(), new State("test2", 456));

		assertEquals(List.of("test", "test2"), strings);
		assertEquals(List.of(123, 456), integers);
	}

	@Test
	void staticHookMethod_throws() {
		class Hooks {
			@Hook("/string") static void stringChanged() { }
		}
		assertThrows(InvalidTypeException.class, () -> HookScanner.registerHooks(new Hooks(), bosk.rootReference(), bosk.hookRegistrar(), MethodHandles.lookup()));
	}

	@Test
	void privateHookMethod_throws() {
		class Hooks {
			@Hook("/string") private void stringChanged() { }
		}
		assertThrows(InvalidTypeException.class, () -> HookScanner.registerHooks(new Hooks(), bosk.rootReference(), bosk.hookRegistrar(), MethodHandles.lookup()));
	}

	@Test
	void inheritedHooks_works() throws InvalidTypeException {
		class ParentHooks {
			@Hook("/string") void stringChanged() { }
		}
		class ChildHooks extends ParentHooks {
			@Hook("/integer") void integerChanged() { }
		}

		record Registration(String name, Reference<?> scope) { }
		List<Registration> registrations = new ArrayList<>();
		HookScanner.registerHooks(new ChildHooks(), bosk.rootReference(), new HookRegistrar() {
			@Override
			public <T> void registerHook(String name, Reference<T> scope, BoskHook<T> hook) {
				registrations.add(new Registration(name, scope));
			}
		}, MethodHandles.lookup());

		assertEquals(List.of(
			new Registration("stringChanged", refs.string()),
			new Registration("integerChanged", refs.integer())
		), registrations);
	}

}
