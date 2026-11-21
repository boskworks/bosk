package works.bosk.libtesting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Slf4jInitializerTest {
	@Test
	void listenerRanBeforeTests() {
		assertTrue(Slf4jInitializer.hasRun(),
			"Slf4jInitializer must be registered and run before any test executes");
	}
}
