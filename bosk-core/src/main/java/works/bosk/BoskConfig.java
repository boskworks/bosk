package works.bosk;

import static java.util.Objects.requireNonNull;

/**
 * @param driverFactory         the factory used to build the driver stack
 * @param registrarFactory      the factory used to build the hook registrar
 * @param hookOverloadThreshold the number of queued hook notifications at which the driver
 *                              starts compacting. An update that matches any hook registration
 *                              queues one notification, and registering a hook queues one too.
 *                              Zero disables standard mode: every update is compacted.
 */
public record BoskConfig<R extends StateTreeNode> (
	DriverFactory<R> driverFactory,
	RegistrarFactory registrarFactory,
	int hookOverloadThreshold
) {

	public BoskConfig {
		driverFactory = requireNonNull(driverFactory);
		registrarFactory = requireNonNull(registrarFactory);
		if (hookOverloadThreshold < 0) {
			throw new IllegalArgumentException("hookOverloadThreshold must not be negative");
		}
	}

	/**
	 * Uses the default hook overload threshold.
	 */
	public BoskConfig(DriverFactory<R> driverFactory, RegistrarFactory registrarFactory) {
		this(driverFactory, registrarFactory, DEFAULT_HOOK_OVERLOAD_THRESHOLD);
	}

	/**
	 * Calls {@code BoskConfig.builder().build()} and returns the result.
	 * To customize the config, it's convenient to inline this method
	 * and then call some additional methods on the builder.
	 */
	public static <RR extends StateTreeNode> BoskConfig<RR> simple() {
		return BoskConfig.<RR>builder().build();
	}

	public static <R extends StateTreeNode> Builder<R> builder() {
		return new Builder<>();
	}

	/**
	 * @return a {@link DriverFactory} with only the basic functionality.
	 */
	@SuppressWarnings("unchecked")
	public static <RR extends StateTreeNode> DriverFactory<RR> simpleDriver() {
		return (DriverFactory<RR>) SIMPLE_DRIVER_FACTORY;
	}

	/**
	 * @return a {@link RegistrarFactory} with only the basic functionality.
	 */
	public static RegistrarFactory simpleRegistrar() {
		return SIMPLE_REGISTRAR_FACTORY;
	}

	public static class Builder<R extends StateTreeNode> {
		private DriverFactory<R> driverFactory;
		private RegistrarFactory registrarFactory;
		private int hookOverloadThreshold = DEFAULT_HOOK_OVERLOAD_THRESHOLD;

		Builder() {
			driverFactory = simpleDriver();
			registrarFactory = simpleRegistrar();
		}

		public Builder<R> driverFactory(DriverFactory<R> driverFactory) {
			this.driverFactory = requireNonNull(driverFactory);
			return this;
		}

		public Builder<R> registrarFactory(RegistrarFactory registrarFactory) {
			this.registrarFactory = requireNonNull(registrarFactory);
			return this;
		}

		public Builder<R> hookOverloadThreshold(int hookOverloadThreshold) {
			this.hookOverloadThreshold = hookOverloadThreshold;
			return this;
		}

		public BoskConfig<R> build() {
			return new BoskConfig<>(
				this.driverFactory,
				this.registrarFactory,
				this.hookOverloadThreshold
			);
		}

		@Override
		public String toString() {
			return "BoskConfig.Builder(driverFactory=" + this.driverFactory + ", registrarFactory=" + this.registrarFactory + ", hookOverloadThreshold=" + this.hookOverloadThreshold + ")";
		}
	}

	private static final int DEFAULT_HOOK_OVERLOAD_THRESHOLD = 1000;
	private static final DriverFactory<?> SIMPLE_DRIVER_FACTORY = (_, d) -> d;
	private static final RegistrarFactory SIMPLE_REGISTRAR_FACTORY = (_, d) -> d;
}
