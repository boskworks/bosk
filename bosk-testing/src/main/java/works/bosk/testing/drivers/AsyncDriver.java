package works.bosk.testing.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

import static lombok.AccessLevel.PRIVATE;

@RequiredArgsConstructor(access = PRIVATE)
public class AsyncDriver implements BoskDriver {
	private final BoskInfo<?> bosk;
	private final BoskDriver downstream;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	public static <RR extends StateTreeNode> DriverFactory<RR> factory() {
		return AsyncDriver::new;
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		submitAsyncTask("submitReplacement", () -> downstream.submitReplacement(target, newValue));
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		submitAsyncTask("submitConditionalReplacement", () -> downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue));
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		submitAsyncTask("submitConditionalCreation", () -> downstream.submitConditionalCreation(target, newValue));
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		submitAsyncTask("submitDeletion", () -> downstream.submitDeletion(target));
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		submitAsyncTask("submitConditionalDeletion", () -> downstream.submitConditionalDeletion(target, precondition, requiredValue));
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		Semaphore semaphore = new Semaphore(0);
		submitAsyncTask("flush", semaphore::release);
		semaphore.acquire();
		downstream.flush();
	}

	private void submitAsyncTask(String description, Runnable task) {
		LOGGER.debug("Submit {}", description);
		var diagnosticAttributes = bosk.diagnosticContext().getAttributes();
		executor.submit(()->{
			LOGGER.debug("Run {}", description);
			try (var __ = bosk.diagnosticContext().withOnly(diagnosticAttributes)) {
				task.run();
			}
			LOGGER.trace("Done {}", description);
		});
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(AsyncDriver.class);
}
