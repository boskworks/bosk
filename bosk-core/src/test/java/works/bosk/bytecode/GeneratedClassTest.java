package works.bosk.bytecode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.bytecode.Codegen.invokeExact;

public class GeneratedClassTest {
	private static final int NUM_CALLERS = 20;
	ExecutorService executor = Executors.newFixedThreadPool(NUM_CALLERS);

	@Test
	void multithreadedInvocation_works() throws NoSuchMethodException, IllegalAccessException, ExecutionException, InterruptedException {
		MethodHandle returnABC = MethodHandles.lookup().findStatic(GeneratedClassTest.class, "returnABC", methodType(String.class));
		Currier currier = new Currier();
		Foo instance = GeneratedClass.instantiate(
			"TestClass",
			Foo.class,
			getClass().getClassLoader(),
			GeneratedClass.here(),
			currier,
			cb -> cb.withMethodBody("foo", GeneratedClass.mtd(String.class, String.class), PUBLIC.mask(), codeBuilder -> {
				invokeExact(codeBuilder, currier, returnABC, "returnABC");
				codeBuilder.areturn();
			})
		);

		// Have a lot of threads all try to use the object at the same time
		List<Future<String>> results = new ArrayList<>(NUM_CALLERS);
		CountDownLatch latch = new CountDownLatch(NUM_CALLERS+1);
		for (int i = 0; i < NUM_CALLERS; i++) {
			results.add(executor.submit(() -> {
				try {
					latch.countDown();
					latch.await();
				} catch (InterruptedException e) {
					throw new AssertionError(e);
				}
				return instance.foo("hello");
			}));
		}

		// Go!
		latch.countDown();
		for (Future<?> result : results) {
			assertEquals("ABC", result.get());
		}

		executor.shutdown();
	}

	public interface Foo {
		String foo(String arg);
	}

	public static String returnABC() {
		return "ABC";
	}
}
