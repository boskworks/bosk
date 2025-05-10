package works.bosk.bytecode;

import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.lang.invoke.MethodType.methodType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.bytecode.ClassBuilder.here;

public class ClassBuilderTest {
	private static final int NUM_CALLERS = 20;
	ExecutorService executor = Executors.newFixedThreadPool(NUM_CALLERS);
	ClassBuilder<Foo> cb;

	@BeforeEach
	void createClassBuilder() {
		cb = new ClassBuilder<>("TestClass", Foo.class, getClass().getClassLoader(), here());
	}

	@Test
	void multithreadedBootstrap_works() throws NoSuchMethodException, IllegalAccessException, ExecutionException, InterruptedException {
		cb.beginClass();

		cb.beginMethod(Foo.class.getDeclaredMethod("foo", String.class));
		cb.invokeDynamic("testMethod", new ConstantCallSite(MethodHandles.lookup().findStatic(ClassBuilderTest.class, "returnABC", methodType(String.class))));
		cb.finishMethod();

		Foo instance = cb.buildInstance();

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
