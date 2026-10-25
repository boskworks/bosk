package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoskConfigTest {
	record TestState() implements StateTreeNode {}

	@Test
	void defaultHookQueueLimit() {
		BoskConfig<TestState> config = BoskConfig.<TestState>builder().build();
		assertEquals(1000, config.hookQueueMaxSize(), "The default hook queue limit should be 1000");
	}

	@Test
	void hookQueueLimitMustBePositive() {
		assertThrows(IllegalArgumentException.class,
			() -> BoskConfig.<TestState>builder().hookQueueMaxSize(0).build(),
			"A non-positive hook queue limit is not meaningful");
	}
}
