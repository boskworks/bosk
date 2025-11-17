package works.bosk.drivers.mongo.status;

import works.bosk.drivers.mongo.internal.Manifest;

public record ManifestStatus(
	Manifest expected,
	Manifest actual
) {
	public boolean isIdentical() {
		return expected.equals(actual);
	}
}
