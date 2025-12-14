package works.bosk.testing.drivers;

import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Reference;
import works.bosk.annotations.ReferencePath;
import works.bosk.drivers.ForwardingDriver;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class ForgottenFlushTest extends AbstractDriverTest {

	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
	}

	@Test
	void testForgottenFlush() throws InvalidTypeException {
		DriverFactory<TestEntity> driverFactory = DriverStateVerifier.wrap(ForgetfulDriver.factory(), TestEntity.class, AbstractDriverTest::initialRoot);
		var bosk = new Bosk<>(
			"ForgottenFlushTest",
			TestEntity.class,
			AbstractDriverTest::initialRoot,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		var refs = bosk.buildReferences(Refs.class);
		var driver = bosk.driver();
		driver.submitReplacement(refs.string(), "new value");
		assertThrows(AssertionError.class, driver::flush,
			"DriverStateVerifier did not detect forgotten flush()");
	}

	static class ForgetfulDriver extends ForwardingDriver {
		public ForgetfulDriver(BoskDriver downstream) {
			super(downstream);
		}

		public static DriverFactory<TestEntity> factory() {
			return (_, d) -> new ForgetfulDriver(d);
		}

		@Override
		public void flush() {
			// oops!
		}
	}
}
