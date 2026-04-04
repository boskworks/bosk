package works.bosk;

import works.bosk.BoskContext.Tenant;

import static java.util.Objects.requireNonNull;

public record BoskConfig<R extends StateTreeNode> (
	DriverFactory<R> driverFactory,
	RegistrarFactory registrarFactory,
	TenancyModel tenancyModel
) {

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
		private TenancyModel tenancyModel;

		Builder() {
			driverFactory = simpleDriver();
			registrarFactory = simpleRegistrar();
			tenancyModel = TenancyModel.NONE;
		}

		public Builder<R> driverFactory(DriverFactory<R> driverFactory) {
			this.driverFactory = requireNonNull(driverFactory);
			return this;
		}

		public Builder<R> registrarFactory(RegistrarFactory registrarFactory) {
			this.registrarFactory = requireNonNull(registrarFactory);
			return this;
		}

		public Builder<R> noTenants() {
			return tenancyModel(TenancyModel.NONE);
		}

		public Builder<R> fixedTenant(Identifier tenantId) {
			return tenancyModel(new TenancyModel.Fixed(tenantId));
		}

		public Builder<R> transientTenants() {
			return tenancyModel(TenancyModel.TRANSIENT);
		}

//		public Builder<R> persistentTenants() {
//			return tenancyModel(TenancyModel.PERSISTENT);
//		}

		public Builder<R> tenancyModel(TenancyModel tenancyModel) {
			this.tenancyModel = requireNonNull(tenancyModel);
			return this;
		}

		public BoskConfig<R> build() {
			return new BoskConfig<>(
				this.driverFactory,
				this.registrarFactory,
				this.tenancyModel
			);
		}

		@Override
		public String toString() {
			return "BoskConfig.Builder(driverFactory=" + this.driverFactory + ", registrarFactory=" + this.registrarFactory + ")";
		}
	}

	public sealed interface TenancyModel {
		/**
		 * All threads are automatically {@link Tenant.Established},
		 * so driver updates can be called without first establishing a tenant on the thread.
		 */
		sealed interface Implicit extends TenancyModel {}

		/**
		 * All threads are initially {@link Tenant.NotEstablished not established}.
		 * Most bosk operations won't work until a tenant is {@link works.bosk.BoskContext#withTenant(Tenant.Established) established}.
		 */
		sealed interface Explicit extends TenancyModel {}

		/**
		 * {@link Tenant.None} is automatically established on all threads, including in hooks.
		 * <p>
		 * This is a good default choice for a bosk that doesn't yet need multitenancy.
		 */
		record None() implements Implicit {}

		/**
		 * {@link Tenant.SetTo} is automatically established on all threads,
		 * and the tenant is fixed to the given identifier, including in hooks.
		 * <p>
		 * This can be a useful first step during a transition to multitenancy,
		 * updating databases or other systems to become tenant-aware with a single tenant
		 * without having to update all application code to establish the tenant context.
		 */
		record Fixed(Identifier id) implements Implicit {}

		/**
		 * Tenant information is not stored in the bosk state
		 * and is only propagated by driver updates.
		 * <p>
		 * Tenant information is not propagated into hooks:
		 * because it's not stored in the bosk state, any hooks that fire initially upon registration can't be given tenant information,
		 * and so for consistency, <em>no</em> hooks get tenant information.
		 * <p>
		 * This is useful in a shared-tree system, where all tenants use the same bosk state,
		 * and the tenant information, while reliable, is advisory only
		 * and has no other effect on reads or updates.
		 * <p>
		 * <em>Evolution note</em>: Due to the weirdness around hooks,
		 * this tenancy model is likely to disappear.
		 */
		record Transient() implements Explicit {}

//		/**
//		 * Tenant information is stored in the bosk state, and is propagated into hooks.
//		 * <p>
//		 * This is useful in a multi-tree system, where each tenant has its own state,
//		 * since tenant information is essential for disambiguating reads and updates.
//		 */
//		record Persistent() implements Explicit {}

		/**
		 * @see works.bosk.BoskConfig.TenancyModel.None
		 */
		None NONE = new None();

		/**
		 * @see works.bosk.BoskConfig.TenancyModel.Transient
		 */
		Transient TRANSIENT = new Transient();

//		/**
//		 * @see works.bosk.BoskConfig.TenancyModel.Persistent
//		 */
//		Persistent PERSISTENT = new Persistent();
	}

	private static final DriverFactory<?> SIMPLE_DRIVER_FACTORY = (_, d) -> d;
	private static final RegistrarFactory SIMPLE_REGISTRAR_FACTORY = (_, d) -> d;
}
