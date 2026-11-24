package works.bosk.drivers.mongo.internal;

import org.junit.jupiter.api.Test;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisconnectedDriverTest {

	@Test
	void formatChangedDuringUpdate_immediateReconnect() {
		var driver = new DisconnectedDriver<TestEntity>(
			new FormatChangedDuringUpdateException("Collection format changed"));
		assertThrows(ImmediateReconnectException.class, driver::flush);
	}

	@Test
	void otherReason_ordinaryDisconnect() {
		var driver = new DisconnectedDriver<TestEntity>(
			new IllegalStateException("Something else went wrong"));
		DisconnectedException e = assertThrows(DisconnectedException.class, driver::flush);
		assertEquals(DisconnectedException.class, e.getClass(),
			"Only a format change warrants an immediate reconnect");
	}

}
