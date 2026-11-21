package works.bosk.load;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadRunnerTest {

	@ParameterizedTest
	@EnumSource(FlushMode.class)
	void runsAndVerifies(FlushMode flushMode) throws InvalidTypeException, IOException, InterruptedException {
		LoadConfig config = LoadConfig.builder()
			.driver(DriverKind.SIMPLE)
			.entityCount(200)
			.threads(2)
			.warmup(Duration.ofMillis(100))
			.duration(Duration.ofMillis(500))
			.flushMode(flushMode)
			.flushEvery(2)
			.valueBytes(8)
			.stallTimeout(Duration.ofSeconds(10))
			.outputDirectory(Path.of("build", "test-load-results"))
			.build();

		LoadResult result = new LoadRunner().run(config);

		assertEquals(0, result.errors(), "The run must not see errors: " + result.firstError());
		assertTrue(result.verified(), result.verificationMessage());
		assertFalse(result.stalled(), "Workers must stop before the stall timeout");
		assertTrue(result.ops() > 0, "At least one operation must complete during measurement");
		if (flushMode == FlushMode.END_ONLY) {
			assertEquals(0, result.flushLatency().count(), "End-only mode must not flush during measurement");
		} else {
			assertTrue(result.flushLatency().count() > 0, "This flush mode must record flushes");
		}
	}
}
