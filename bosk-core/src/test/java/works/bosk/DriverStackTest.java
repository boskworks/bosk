package works.bosk;

import org.junit.jupiter.api.Test;
import works.bosk.drivers.ForwardingDriver;
import works.bosk.drivers.NoOpDriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DriverStackTest {
	final BoskDriver baseDriver = new NoOpDriver();

	@Test
	void emptyStack_returnsDownstream() {
		BoskDriver actual = DriverStack.<AbstractBoskTest.TestEntity>of().build(null, baseDriver);
		assertSame(baseDriver, actual);
	}

	@Test
	void stackedDrivers_correctOrder() {
		DriverStack<AbstractBoskTest.TestEntity> stack = DriverStack.of(
			(_, d) -> new TestDriver("first", d),
			(_, d) -> new TestDriver("second", d)
		);

		TestDriver firstDriver = (TestDriver) stack.build(null, baseDriver);
		TestDriver secondDriver = (TestDriver) firstDriver.downstream();
		BoskDriver thirdDriver = secondDriver.downstream();

		assertEquals("first", firstDriver.name);
		assertEquals("second", secondDriver.name);
		assertSame(baseDriver, thirdDriver);
	}

	static class TestDriver extends ForwardingDriver {
		final String name;

		public TestDriver(String name, BoskDriver downstream) {
			super(downstream);
			this.name = name;
		}

		BoskDriver downstream() {
			return downstream;
		}

		@Override
		public String toString() {
			return name;
		}
	}
}
