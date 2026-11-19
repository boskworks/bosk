package works.bosk.drivers.mongo.internal;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests that updates applied from the change stream carry the diagnostic
 * attributes of the update that wrote them.
 */
@ReplayLogsOnFailure
@InjectFields
public class DiagnosticAttributesTest extends AbstractMongoDriverTest {

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.standardDriverSettings();
	}

	@Test
	void loadedStateSeedsTheFieldTracker() throws InvalidTypeException, IOException, InterruptedException {
		Bosk<TestEntity> writer = newBosk();
		Refs writerRefs = writer.buildReferences(Refs.class);
		writer.driver().flush();

		// Give the document a diagnostics field that the next write leaves unchanged.
		try (var _ = writer.context().withAttribute("test", "original")) {
			writer.driver().submitReplacement(
				writerRefs.catalog().then(entity123).then(String.class, "string"), "first");
		}
		writer.driver().flush();

		// This driver loads the state, so it must learn the document's diagnostics
		// from the document itself: the next event will not carry them.
		Bosk<TestEntity> reader = newBosk();
		Refs readerRefs = reader.buildReferences(Refs.class);
		CountDownLatch hookRan = new CountDownLatch(1);
		AtomicReference<String> attributeSeenByHook = new AtomicReference<>();
		reader.hookRegistrar().registerHook("record", readerRefs.catalog().then(entity123).then(String.class, "string"), ref -> {
			if ("second".equals(ref.value())) {
				attributeSeenByHook.set(reader.context().getAttribute("test"));
				hookRan.countDown();
			}
		});

		try (var _ = writer.context().withAttribute("test", "original")) {
			writer.driver().submitReplacement(
				writerRefs.catalog().then(entity123).then(String.class, "string"), "second");
		}
		writer.driver().flush();
		reader.driver().flush();

		assertTrue(hookRan.await(30, SECONDS), "The reader's hook must run for the update");
		assertEquals("original", attributeSeenByHook.get(),
			"The reader must use the diagnostics stored in the document it loaded");
	}


	private Bosk<TestEntity> newBosk() throws InvalidTypeException {
		return new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
	}
}
