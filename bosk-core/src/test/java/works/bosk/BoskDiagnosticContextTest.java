package works.bosk;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.ReferencePath;
import works.bosk.testing.drivers.AbstractDriverTest;
import works.bosk.testing.drivers.DriverConformanceTest;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Note that context propagation for driver operations is tested by {@link DriverConformanceTest}.
 */
class BoskDiagnosticContextTest extends AbstractDriverTest {

	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
	}

	@BeforeEach
	void setupBosk() {
		bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractDriverTest::initialRoot,
			Bosk.simpleDriver(),
			Bosk.simpleRegistrar());
	}

	@Test
	void hookRegistration_propagatesDiagnosticContext() throws IOException, InterruptedException {
		Semaphore diagnosticsVerified = new Semaphore(0);
		bosk.driver().flush();
		try (var _ = bosk.diagnosticContext().withAttribute("attributeName", "attributeValue")) {
			bosk.registerHook("contextPropagatesToHook", bosk.rootReference(), ref -> {
				assertEquals("attributeValue", bosk.diagnosticContext().getAttribute("attributeName"));
				assertEquals(MapValue.singleton("attributeName", "attributeValue"), bosk.diagnosticContext().getAttributes());
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
		var diagnosticContext = bosk.diagnosticContext();
		try (var _ = diagnosticContext.withAttributes(expectedOuter)) {
			assertEquals(expectedOuter, diagnosticContext.getAttributes());
			try (var __ = diagnosticContext.withReplacedPrefix("prefix.", overrides)) {
				assertEquals(expectedInner, diagnosticContext.getAttributes());
			}
		}
	}
}
