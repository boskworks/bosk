package works.bosk.exceptions;

import works.bosk.Listing;
import works.bosk.Reference;

/**
 * Thrown when {@link Reference#value()} is called and the referenced object does not exist.
 * Also thrown by analogous methods like {@link Listing#getValue}.
 */
public class NonexistentReferenceException extends RuntimeException {
	public NonexistentReferenceException(Reference<?> reference) {
		super(message(reference));
	}

	public NonexistentReferenceException(Reference<?> reference, Throwable cause) {
		super(message(reference), cause);
	}

	private static String message(Reference<?> reference) {
		return "Reference to nonexistent " + reference.targetClass().getSimpleName() + ": \"" + reference.path() + "\"";
	}

}
