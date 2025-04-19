package works.bosk;

public interface RegistrarFactory {
	HookRegistrar build(BoskInfo<?> boskInfo, HookRegistrar downstream);
}
