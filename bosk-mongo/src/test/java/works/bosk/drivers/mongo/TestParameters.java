package works.bosk.drivers.mongo;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.Value;

import static java.util.stream.Collectors.toList;

public class TestParameters {
	private static final AtomicInteger dbCounter = new AtomicInteger(0);

	@Value
	public static class ParameterSet {
		String name;
		MongoDriverSettings.MongoDriverSettingsBuilder driverSettingsBuilder;

		public static ParameterSet from(MongoDriverSettings.DatabaseFormat format, EventTiming timing) {
			String dbName = TestParameters.class.getSimpleName()
				+ "_" + dbCounter.incrementAndGet()
				+ "_" + format.getClass().getSimpleName()
				+ timing.suffix;
			return new ParameterSet(
				timing + "," + format,
				MongoDriverSettings.builder()
					.preferredDatabaseFormat(format)
					.timescaleMS(90_000) // Tests that actually exercise timeouts should use a much shorter value
					.testing(MongoDriverSettings.Testing.builder()
						.eventDelayMS(timing.eventDelayMS)
						.build())
					.database(dbName)
			);
		}

		public ParameterSet applyDriverSettings(Consumer<MongoDriverSettings.MongoDriverSettingsBuilder> setterUpper) {
			setterUpper.accept(driverSettingsBuilder);
			return this;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	@SuppressWarnings("unused")
	static Stream<ParameterSet> driverSettings(
		Stream<MongoDriverSettings.DatabaseFormat> formats,
		Stream<EventTiming> timings
	) {
		List<EventTiming> timingsList = timings.collect(toList());
		return formats
			.flatMap(f -> timingsList.stream()
				.map(e -> ParameterSet.from(f,e)));
	}

	public enum EventTiming {
		NORMAL(0, ""),

		/**
		 * Updates are delayed to give events a chance to arrive first
		 */
		EARLY(-200, "_early"),

		/**
		 * Events are delayed to give other logic a chance to run first
		 */
		LATE(200, "_late");

		final int eventDelayMS;
		final String suffix;

		EventTiming(int eventDelayMS, String suffix) {
			this.eventDelayMS = eventDelayMS;
			this.suffix = suffix;
		}
	}

}
