package works.bosk.drivers.mongo.internal;

import org.junit.jupiter.api.Test;
import works.bosk.exceptions.InvalidTypeException;

import static works.bosk.TypeValidation.validateType;

/**
 * Tests of {@link Manifest}, the document that records which format and format
 * version a collection is stored in. This guards the manifest's own structure,
 * which must remain a valid bosk state type as the driver evolves.
 */
public class ManifestTest {
	@Test
	void manifest_passesTypeValidation() throws InvalidTypeException {
		validateType(Manifest.class);
	}

}
