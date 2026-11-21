package works.bosk.load;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoadConfigParserTest {

	@Test
	void absoluteThreadCount() {
		assertEquals(3, LoadConfigParser.parseThreads("3", 10));
	}

	@Test
	void threadCountRelativeToProcessors() {
		assertEquals(10, LoadConfigParser.parseThreads("P", 10));
		assertEquals(5, LoadConfigParser.parseThreads("P/2", 10));
		assertEquals(20, LoadConfigParser.parseThreads("2P", 10));
		assertEquals(40, LoadConfigParser.parseThreads("4*P", 10));
	}

	@Test
	void invalidThreadCountIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> LoadConfigParser.parseThreads("many", 10));
	}

	@Test
	void durationsUseExplicitUnits() {
		assertEquals(Duration.ofMillis(500), LoadConfigParser.parseDuration("warmup", "500ms"));
		assertEquals(Duration.ofSeconds(2), LoadConfigParser.parseDuration("warmup", "2s"));
		assertEquals(Duration.ofMinutes(3), LoadConfigParser.parseDuration("warmup", "3m"));
		assertEquals(Duration.ofHours(1), LoadConfigParser.parseDuration("warmup", "1h"));
	}

	@Test
	void invalidDurationIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> LoadConfigParser.parseDuration("warmup", "10"));
	}

	@Test
	void flushModesAreCaseInsensitive() {
		assertEquals(FlushMode.EVERY_OP, LoadConfigParser.parseFlushMode("everyOp"));
		assertEquals(FlushMode.EVERY_N, LoadConfigParser.parseFlushMode("every_n"));
		assertEquals(FlushMode.END_ONLY, LoadConfigParser.parseFlushMode("end"));
	}

	@Test
	void parsesACompleteConfiguration() {
		LoadConfig config = LoadConfigParser.parse(new String[] {
			"--driver=simple",
			"--entities=123",
			"--threads=3",
			"--warmup=1s",
			"--duration=2m",
			"--flush=everyN",
			"--flushEvery=7",
			"--valueBytes=5",
			"--hookLatency=1ms",
			"--hookScope=entity",
			"--hookCount=3",
			"--hookQueueMaxSize=17",
			"--seed=42",
			"--label=example",
			"--out=somewhere"
		});
		assertEquals(DriverKind.SIMPLE, config.driver());
		assertEquals(123, config.entityCount());
		assertEquals(3, config.threads());
		assertEquals(Duration.ofSeconds(1), config.warmup());
		assertEquals(Duration.ofMinutes(2), config.duration());
		assertEquals(FlushMode.EVERY_N, config.flushMode());
		assertEquals(7, config.flushEvery());
		assertEquals(5, config.valueBytes());
		assertEquals(Duration.ofMillis(1), config.hookLatency());
		assertEquals(HookScope.ENTITY, config.hookScope());
		assertEquals(3, config.hookCount());
		assertEquals(17, config.hookQueueMaxSize());
		assertEquals(42, config.seed());
		assertEquals("example", config.label());
		assertEquals(Path.of("somewhere"), config.outputDirectory());
	}
}
