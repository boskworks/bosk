package works.bosk;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.BoskConfig.TenancyModel.Explicit;
import works.bosk.BoskConfig.TenancyModel.Implicit;
import works.bosk.BoskContext.Tenant;
import works.bosk.annotations.ReferencePath;
import works.bosk.testing.drivers.AbstractDriverTest;
import works.bosk.testing.drivers.DriverConformanceTest;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.BoskContext.Tenant.NOT_ESTABLISHED;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Note that context propagation for driver operations is tested by {@link DriverConformanceTest}.
 */
class BoskContextTest extends AbstractDriverTest {
	final Tenant.SetTo tenant1 = Tenant.setTo(Identifier.from("tenant1"));
	final Tenant.SetTo tenant2 = Tenant.setTo(Identifier.from("tenant2"));

	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
	}

	@BeforeEach
	void setupBosk() {
		bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			this::initialState,
			BoskConfig.<TestEntity>builder()
				.tenancyModel(scenario.tenancyModel)
				.build()
		);
	}

	@Test
	void hookRegistration_propagatesContext() throws IOException, InterruptedException {
		Semaphore diagnosticsVerified = new Semaphore(0);
		bosk.driver().flush();
		try (var _ = bosk.context().withAttribute("attributeName", "attributeValue")) {
			bosk.hookRegistrar().registerHook("contextPropagatesToHook", bosk.rootReference(), _ -> {
				assertEquals("attributeValue", bosk.context().getAttribute("attributeName"));
				assertEquals(MapValue.singleton("attributeName", "attributeValue"), bosk.context().getAttributes());
				diagnosticsVerified.release();
			});
		}
		bosk.driver().flush();
		assertTrue(diagnosticsVerified.tryAcquire(5, SECONDS));
	}

	@Test
	void replacePrefix_works() {
		MapValue<String> expectedOuter = MapValue.copyOf(Map.of(
			"unprefixed", "unprefixedValue",
			"prefix.key1", "outer1",
			"prefix.key2", "outer2"
		));
		MapValue<String> overrides = MapValue.copyOf(Map.of(
			"key1", "inner1",
			"key3", "inner3"
		));
		MapValue<String> expectedInner = MapValue.copyOf(Map.of(
			"unprefixed", "unprefixedValue",
			"prefix.key1", "inner1",
			"prefix.key3", "inner3"
		));
		var context = bosk.context();
		try (var _ = context.withAttributes(expectedOuter)) {
			assertEquals(expectedOuter, context.getAttributes());
			try (var _ = context.withReplacedPrefix("prefix.", overrides)) {
				assertEquals(expectedInner, context.getAttributes());
			}
		}
	}

	@Test
	void validTenant_works() {
		var context = bosk.context();
		assertEquals(scenario.automaticallyEstablishedTenant(), context.getTenant());
		try (var _ = context.withMaybeTenant(scenario.startingTenant)) {
			assertEquals(scenario.startingTenant, context.getTenant());
		}
		assertEquals(scenario.automaticallyEstablishedTenant(), context.getTenant());

		switch (scenario.tenancyModel) {
			case Implicit _ -> {
				assertThrows(IllegalArgumentException.class, () -> context.withTenant(tenant2).close(),
					"Must not allow establishing different tenant");
			}
			case Explicit _ -> {
				assertEquals(NOT_ESTABLISHED, context.getTenant());
				try (var _ = context.withTenant(tenant1)) {
					assertEquals(tenant1, context.getTenant());
					assertThrows(IllegalArgumentException.class, () -> context.withTenant(tenant2).close(),
						"Must not allow establishing different tenant on the same thread");
					assertThrows(IllegalArgumentException.class, () -> context.withMaybeTenant(NOT_ESTABLISHED).close(),
						"Must not allow un-establishing tenant");
				}
				assertEquals(NOT_ESTABLISHED, context.getTenant(),
					"Tenant should be reset to initial tenant after closing ContextScope");
			}
		}
	}

	@Test
	void withTenant_perThread() throws ExecutionException, InterruptedException, TimeoutException {
		if (scenario.tenancyModel instanceof Implicit) {
			// Not relevant; every thread will have the same tenant anyway
			return;
		}
		try (var virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
			var context = bosk.context();

			try (var _ = context.withTenant(tenant1)) {
				assertEquals(tenant1, context.getTenant());
				virtualThreads.submit(()-> {
					assertEquals(NOT_ESTABLISHED, context.getTenant(),
						"New thread should not inherit tenant");
					try (var _ = context.withTenant(tenant2)) {
						assertEquals(tenant2, context.getTenant(),
							"Should be able to establish a distinct tenant on a different thread");
					}
					assertEquals(NOT_ESTABLISHED, context.getTenant(),
						"Tenant should be reset when ContextScope is closed");
				}).get(10, SECONDS);
				assertEquals(tenant1, context.getTenant(),
					"Tenant on original thread should be unaffected by operations on other thread");
			}

			assertEquals(NOT_ESTABLISHED, context.getTenant());
		}
	}

	@Test
	void wrongOrder_throws() {
		var context = bosk.context();
		try (
			var scope1 = context.withAttribute("key", "scope1");
			var _ = context.withAttribute("key", "scope2")
		) {
			assertThrows(IllegalStateException.class, scope1::close,
				"Closing ContextScopes in the wrong order should throw");
		}

		// (If we made it to this point, we were able to close the scopes
		// in the correct order even after trying to close them in the wrong order.)
	}
}
