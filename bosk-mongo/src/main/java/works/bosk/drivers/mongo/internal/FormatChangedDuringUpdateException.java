package works.bosk.drivers.mongo.internal;

/**
 * An update was planned against a format that has since been refurbished away.
 * <p>
 * The update's writes match nothing, and cannot be reported as applied. The
 * driver disconnects in response, and the retry re-plans the update against
 * the format that replaced it.
 */
class FormatChangedDuringUpdateException extends Exception {
	public FormatChangedDuringUpdateException(String message) {
		super(message);
	}
}
