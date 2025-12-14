package works.bosk;

import static java.util.Objects.requireNonNull;

public final class BoskConfig<R extends StateTreeNode> {
	private final DriverFactory<R> driverFactory;
	private final RegistrarFactory registrarFactory;

	private BoskConfig(
		DriverFactory<R> driverFactory,
		RegistrarFactory registrarFactory
	) {
		this.driverFactory = driverFactory;
		this.registrarFactory = registrarFactory;
	}

	@SuppressWarnings("unchecked")
	public static <RR extends StateTreeNode> BoskConfig<RR> simple() {
		return (BoskConfig<RR>) SIMPLE_CONFIG;
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

	public DriverFactory<R> driverFactory() {
		return driverFactory;
	}

	public RegistrarFactory registrarFactory() {
		return registrarFactory;
	}

	public static class Builder<R extends StateTreeNode> {
		private DriverFactory<R> driverFactory;
		private RegistrarFactory registrarFactory;

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

		public BoskConfig<R> build() {
			return new BoskConfig<R>(this.driverFactory, this.registrarFactory);
		}

		@Override
		public String toString() {
			return "BoskConfig.Builder(driverFactory=" + this.driverFactory + ", registrarFactory=" + this.registrarFactory + ")";
		}
	}

	private static final DriverFactory<?> SIMPLE_DRIVER_FACTORY = (_, d) -> d;
	private static final RegistrarFactory SIMPLE_REGISTRAR_FACTORY = (_, d) -> d;
	private static final BoskConfig<?> SIMPLE_CONFIG = new BoskConfig<>(SIMPLE_DRIVER_FACTORY, SIMPLE_REGISTRAR_FACTORY);
}
