package works.bosk.drivers.mongo.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.internal.Manifest;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

/**
 * Info about the state of the database, highlighting how it differs from the desired state.
 * TODO: Unit tests
 */
public record MongoStatus(
	@JsonInclude(NON_NULL) String error,
	ManifestStatus manifest,
	StateStatus state
) {
	public MongoStatus with(DatabaseFormat preferredFormat, StateTreeNode actualManifest) {
		return new MongoStatus(
			this.error,
			new ManifestStatus(
				Manifest.forFormat(preferredFormat),
				actualManifest
			),
			this.state
		);
	}

	public boolean isAllClear() {
		return manifest.isIdentical()
			&& state.difference() instanceof NoDifference;
	}
}
