package works.bosk;

/**
 * Creates {@link HookRegistrar}s with custom behaviour, such as logging or propagating telemetry context.
 * <p>
 * Factories are chained together to form a processing pipeline, with each factory wrapping
 * the {@code downstream} registrar to add its own logic before delegating hook registrations.
 */
public interface RegistrarFactory {
	/**
	 * @param boskInfo information about the bosk to which hooks will be registered
	 * @param downstream the next registrar in the chain
	 * @return a registrar that applies this factory's logic and delegates to {@code downstream}
	 */
	HookRegistrar build(BoskInfo<?> boskInfo, HookRegistrar downstream);
}
