package works.bosk.testing.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Random;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

public final class JitterDriver implements BoskDriver {
	private final BoskDriver downstream;
	private final DoubleSupplier jitter;

	public static <RR extends StateTreeNode> DriverFactory<RR> factory(double meanMillis, double limitMillis, long seed) {
		return (b,d) -> new JitterDriver(d, meanMillis, limitMillis, seed);
	}

	private JitterDriver(BoskDriver downstream, double meanMillis, double limitMillis, long seed) {
		this.downstream = downstream;
		Random random = new Random(seed);

		//http://en.wikipedia.org/wiki/Exponential_distribution#Random_variate_generation
		jitter = () -> Double.min(limitMillis,
			-Math.log(random.nextDouble()) * meanMillis
		);
	}

	private void sleep() {
		try {
			long totalNanos = (long)(1e6 * jitter.getAsDouble());
			long ms = totalNanos / 1_000_000;
			int nanos = (int) (totalNanos % 1_000_000);
			LOGGER.trace("Sleeping for {} ms", totalNanos/1e6);
			Thread.sleep(ms, nanos);
			LOGGER.trace("Done sleeping");
		} catch (InterruptedException e) {
			LOGGER.debug("Sleep interrupted", e);
		}
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		sleep();
		return downstream.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		sleep();
		downstream.submitReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		sleep();
		downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		sleep();
		downstream.submitConditionalCreation(target, newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		sleep();
		downstream.submitDeletion(target);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		sleep();
		downstream.submitConditionalDeletion(target, precondition, requiredValue);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		sleep();
		downstream.flush();
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(JitterDriver.class);
}
