package works.bosk;

import static java.util.Objects.requireNonNull;

/**
 * @param driverFactory      the factory used to build the driver stack
 * @param registrarFactory   the factory used to build the hook registrar
 * @param hookQueueMaxSize   the maximum number of queued hook executions before the local driver
 *                           switches to overload mode; defaults to 1000. Raising the limit allows
 *                           more state snapshots to accumulate before coalescing, so it also raises
 *                           the queue's memory bound. The same value is the base probation budget
 *                           and the unit in which the probation multiplier decays, so lowering it
 *                           also shortens probation.
 */
public record BoskConfig<R extends StateTreeNode> (
	DriverFactory<R> driverFactory,
	RegistrarFactory registrarFactory,
	int hookQueueMaxSize
) {

	public BoskConfig {
		driverFactory = requireNonNull(driverFactory);
		registrarFactory = requireNonNull(registrarFactory);
		if (hookQueueMaxSize < 1) {
			throw new IllegalArgumentException("hookQueueMaxSize must be positive");
		}
	}

	/**
	 * Uses the default hook queue limit.
	 */
	public BoskConfig(DriverFactory<R> driverFactory, RegistrarFactory registrarFactory) {
		this(driverFactory, registrarFactory, DEFAULT_HOOK_QUEUE_MAX_SIZE);
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
		private int hookQueueMaxSize = DEFAULT_HOOK_QUEUE_MAX_SIZE;

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

		/**
		 * Sets the number of queued hook executions at which the local driver
		 * switches to overload mode. This value is also the base probation budget
		 * and the unit in which the probation multiplier decays.
		 */
		public Builder<R> hookQueueMaxSize(int hookQueueMaxSize) {
			this.hookQueueMaxSize = hookQueueMaxSize;
			return this;
		}

		public BoskConfig<R> build() {
			return new BoskConfig<>(
				this.driverFactory,
				this.registrarFactory,
				this.hookQueueMaxSize
			);
		}

		@Override
		public String toString() {
			return "BoskConfig.Builder(driverFactory=" + this.driverFactory + ", registrarFactory=" + this.registrarFactory + ", hookQueueMaxSize=" + this.hookQueueMaxSize + ")";
		}
	}

	private static final int DEFAULT_HOOK_QUEUE_MAX_SIZE = 1000;
	private static final DriverFactory<?> SIMPLE_DRIVER_FACTORY = (_, d) -> d;
	private static final RegistrarFactory SIMPLE_REGISTRAR_FACTORY = (_, d) -> d;
}
