package works.bosk.drivers.mongo;

import org.junit.jupiter.api.Test;
import works.bosk.exceptions.InvalidTypeException;

import static works.bosk.TypeValidation.validateType;

public class MiscMongoDriverTest {
	@Test
	void manifest_passesTypeValidation() throws InvalidTypeException {
		validateType(Manifest.class);
	}

}
