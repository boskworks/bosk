package works.bosk.drivers.mongo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.DISCONNECT;

class MongoDriverSettingsTest {

	@Test
	void validate_acceptsValidSettings() {
		MongoDriverSettings settings = MongoDriverSettings.builder()
			.database("db")
			.build();
		settings.validate();
		assertEquals("boskCollection", settings.collection());
		assertEquals(10_000, settings.timescaleMS());
		assertEquals(DISCONNECT, settings.initialDatabaseUnavailableMode());
	}

	@Test
	void validate_requiresADatabaseName() {
		MongoDriverSettings settings = MongoDriverSettings.builder().build();
		assertThrows(IllegalArgumentException.class, settings::validate);
	}

	@Test
	void validate_requiresACollectionName() {
		MongoDriverSettings settings = MongoDriverSettings.builder()
			.database("db")
			.collection(" ")
			.build();
		assertThrows(IllegalArgumentException.class, settings::validate);
	}
}
