package works.bosk;

/**
 * Used to create a thing that can be referenced (eg. as the domain
 * of a {@link Listing}) but that is not actually serialized or instantiated.
 *
 * <p>
 * Behaves like an {@link java.util.Optional Optional} that is always empty.
 */
public final class Phantom<T> {
	public static <T> Phantom<T> empty() {
		@SuppressWarnings("unchecked")
		Phantom<T> instance = (Phantom<T>) INSTANCE;
		return instance;
	}

	@Override
	public String toString() {
		return "Phantom.empty";
	}

	private static final Phantom<?> INSTANCE = new Phantom<>();
}
