package works.bosk.util;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReflectionHelpersTest {
	private final MethodHandles.Lookup lookup = MethodHandles.lookup();

	@Test
	void getDeclaredMethodsInOrder_correctOrder() throws NoSuchMethodException {
		interface ClassWithSomeMethods {
			void first();
			void secondOverloaded(int x);
			void secondOverloaded(long x);
			void secondOverloaded(float x);
			void secondOverloaded(String s);
			void third();
		}

		List<Method> actual = ReflectionHelpers.getDeclaredMethodsInOrder(ClassWithSomeMethods.class, lookup);
		List<Method> expected = List.of(
			ClassWithSomeMethods.class.getDeclaredMethod("first"),
			ClassWithSomeMethods.class.getDeclaredMethod("secondOverloaded", int.class),
			ClassWithSomeMethods.class.getDeclaredMethod("secondOverloaded", long.class),
			ClassWithSomeMethods.class.getDeclaredMethod("secondOverloaded", float.class),
			ClassWithSomeMethods.class.getDeclaredMethod("secondOverloaded", String.class),
			ClassWithSomeMethods.class.getDeclaredMethod("third")
		);
		assertEquals(expected, actual);
	}

	@Test
	void getDeclaredMethodsInOrder_worksWithBuiltInClasses() throws NoSuchMethodException {
		List<Method> actual = ReflectionHelpers.getDeclaredMethodsInOrder(Runnable.class, lookup);
		List<Method> expected = List.of(
			Runnable.class.getDeclaredMethod("run")
		);
		assertEquals(expected, actual);
	}

	@Test
	void getDeclaredMethodsInOrder_ignoresInheritedMethods() throws NoSuchMethodException {
		class Parent {
			@SuppressWarnings("unused")
			void parentMethod() {}
		}
		class Child extends Parent {
			void childMethod() {}
		}

		List<Method> actual = ReflectionHelpers.getDeclaredMethodsInOrder(Child.class, lookup);
		List<Method> expected = List.of(
			Child.class.getDeclaredMethod("childMethod")
		);
		assertEquals(expected, actual);
	}

	@Test
	void getDeclaredMethodsInOrder_excludesConstructorsAndStaticInitializers() throws NoSuchMethodException {
		class ClassWithInitAndClinit {
			static { }
			public ClassWithInitAndClinit() { }
			public void normalMethod() { }
		}

		List<Method> actual = ReflectionHelpers.getDeclaredMethodsInOrder(ClassWithInitAndClinit.class, lookup);
		List<Method> expected = List.of(
			ClassWithInitAndClinit.class.getDeclaredMethod("normalMethod")
		);
		assertEquals(expected, actual);
	}

	@Test
	void getDeclaredMethodsInOrder_failsOnInaccessibleClassEvenWithNoMethods() {
		class InaccessibleClass { }
		assertThrows(IllegalArgumentException.class, () -> ReflectionHelpers.getDeclaredMethodsInOrder(InaccessibleClass.class, MethodHandles.publicLookup()),
			"getDeclaredMethodsInOrder should fail when the class is not accessible to the provided lookup");
	}

	@Test
	void getDeclaredMethodsInOrder_failsOnInaccessibleMethod() {
		assertThrows(IllegalArgumentException.class, () -> ReflectionHelpers.getDeclaredMethodsInOrder(PublicClassWithInaccessibleMethod.class, MethodHandles.publicLookup()),
			"getDeclaredMethodsInOrder should fail when the class contains a method that is not accessible to the provided lookup");
	}

	public static class PublicClassWithInaccessibleMethod {
		@SuppressWarnings("unused")
		void inaccessibleMethod() { }
	}
}
