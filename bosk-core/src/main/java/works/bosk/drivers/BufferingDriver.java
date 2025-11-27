package works.bosk.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskDiagnosticContext;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

import static lombok.AccessLevel.PROTECTED;

/**
 * Queues updates and submits them to a downstream driver when {@link #flush()}
 * is called.
 *
 * <p>
 * This has the effect of causing the whole list of updates to be
 * discarded if an exception is thrown while the updates are being computed,
 * which could be a desirable property. However, the buffered updates are
 * <strong>not</strong> submitted downstream atomically: other updates from other
 * threads may be interleaved. (They are, of course, submitted downstream
 * in the order they were submitted to this driver.)
 *
 * @author pdoyle
 */
@RequiredArgsConstructor(access = PROTECTED)
public class BufferingDriver implements BoskDriver {
	private final BoskDriver downstream;
	private final BoskDiagnosticContext diagnosticContext;
	private final Deque<Consumer<BoskDriver>> updateQueue = new ConcurrentLinkedDeque<>();
	private final AtomicLong changeID = new AtomicLong();

	public static <RR extends StateTreeNode> DriverFactory<RR> factory() {
		return (b,d) -> new BufferingDriver(d, b.diagnosticContext());
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		enqueue(d -> d.submitReplacement(target, newValue));
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		enqueue(d -> d.submitConditionalCreation(target, newValue));
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		enqueue(d -> d.submitDeletion(target));
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		enqueue(d -> d.submitConditionalReplacement(target, newValue, precondition, requiredValue));
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		enqueue(d -> d.submitConditionalDeletion(target, precondition, requiredValue));
	}

	@Override
	public void flush() throws InterruptedException, IOException {
		for (Consumer<BoskDriver> update = updateQueue.pollFirst(); update != null; update = updateQueue.pollFirst()) {
			update.accept(downstream);
		}
		downstream.flush();
	}

	private void enqueue(Consumer<BoskDriver> action) {
		long changeID = this.changeID.incrementAndGet();
		LOGGER.debug("Buffering action {} {}", changeID, diagnosticContext.getAttributes());
		MapValue<String> capturedAttributes = diagnosticContext.getAttributes();
		updateQueue.add(d -> {
			try (var _ = diagnosticContext.withOnly(capturedAttributes)) {
				LOGGER.debug("Running action {} {}", changeID, diagnosticContext.getAttributes());
				action.accept(d);
			}
		});
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(BufferingDriver.class);
}
