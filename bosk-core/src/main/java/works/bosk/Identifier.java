package works.bosk;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;

/**
 * The means by which {@link Entity entities} are identified within
 * a particular domain, such as a {@link Catalog} or {@link SideTable}.
 */
public final class Identifier {
	@NonNull
	final String value;

	private Identifier(@NonNull String value) {
		this.value = value;
	}

	// TODO: Intern these.  No need to have several Identifier objects for the same value
	public static Identifier from(String value) {
		validate(value);
		// TODO: We probably ought to outlaw some characters like NUL (\u0000) but
		//  that's O(n) in the length of the string, so it's not clear that's worth the overhead.
		return new Identifier(value);
	}

	/**
	 * I'm going to regret adding this.
	 */
	public static Identifier unique(String prefix) {
		validate(prefix);
		return new Identifier(prefix + (uniqueIdCounter.incrementAndGet()));
	}

	private static void validate(String value) {
		if (value.isEmpty()) {
			throw new IllegalArgumentException("Identifier can't be empty");
		} else if (value.startsWith("-") || value.endsWith("-")) {
			throw new IllegalArgumentException("Identifier can't start or end with a hyphen");
		}
	}

	private static final AtomicLong uniqueIdCounter = new AtomicLong(1000);

	@Override
	public String toString() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		Identifier that = (Identifier) o;
		return Objects.equals(value, that.value);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(value);
	}
}
