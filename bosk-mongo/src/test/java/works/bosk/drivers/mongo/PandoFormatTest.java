package works.bosk.drivers.mongo;

import org.junit.jupiter.api.Test;
import works.bosk.ListValue;
import works.bosk.drivers.mongo.exceptions.FormatMisconfigurationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.drivers.mongo.PandoFormat.OrphanDocumentMode.EARNEST;
import static works.bosk.drivers.mongo.PandoFormat.OrphanDocumentMode.HASTY;

class PandoFormatTest {

	@Test
	void factoryMethods_defaultToHastyMode() {
		assertEquals(HASTY, PandoFormat.oneBigDocument().orphanDocumentMode());
		assertEquals(HASTY, PandoFormat.withGraftPoints("/catalog").orphanDocumentMode());
	}

	@Test
	void earnestMode_isRejected() {
		assertThrows(FormatMisconfigurationException.class,
			() -> new PandoFormat(ListValue.empty(), EARNEST));
	}

}
