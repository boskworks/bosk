package works.bosk.drivers.mongo.status;

import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.internal.Manifest;

public record ManifestStatus(
	StateTreeNode expected,
	StateTreeNode actual
) {
	public ManifestStatus {
		// These should be Manifest objects, but we don't want to export that type from this module
		assert expected instanceof Manifest;
		assert actual instanceof Manifest;
	}

	public boolean isIdentical() {
		return expected.equals(actual);
	}
}
