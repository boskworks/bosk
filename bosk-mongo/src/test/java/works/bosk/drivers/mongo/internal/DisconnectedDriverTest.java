package works.bosk.drivers.mongo.internal;

import org.junit.jupiter.api.Test;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisconnectedDriverTest {

	@Test
	void revisionFieldDisrupted_immediateReconnect() {
		var driver = new DisconnectedDriver<TestEntity>(
			new RevisionFieldDisruptedException("No root documents found"));
		assertThrows(ImmediateReconnectException.class, driver::flush);
	}

	@Test
	void epochMismatch_immediateReconnect() {
		var driver = new DisconnectedDriver<TestEntity>(
			new EpochMismatchException("Collection epoch has changed"));
		assertThrows(ImmediateReconnectException.class, driver::flush);
	}

	@Test
	void otherReason_ordinaryDisconnect() {
		var driver = new DisconnectedDriver<TestEntity>(
			new IllegalStateException("Something else went wrong"));
		DisconnectedException e = assertThrows(DisconnectedException.class, driver::flush);
		assertEquals(DisconnectedException.class, e.getClass(),
			"Only revision disruptions warrant an immediate reconnect");
	}

}
