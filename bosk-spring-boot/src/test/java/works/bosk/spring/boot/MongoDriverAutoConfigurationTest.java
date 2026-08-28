package works.bosk.spring.boot;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the auto-configuration of a MongoDB driver factory from {@link BoskMongoProperties}.
 */
class MongoDriverAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(MongoDriverAutoConfiguration.class));

	private final ApplicationContextRunner springDataRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class, MongoDriverAutoConfiguration.class));

	@Test
	void providesFactoryAndDefaultSettings() {
		// The auto-configuration must leave bosk's own defaults in place when no
		// bosk.mongodb.* properties are set; the core defaults themselves are asserted in
		// MongoDriverSettingsTest.
		runner.run(context -> {
			assertThat(context).hasSingleBean(MongoDriver.MongoDriverFactory.class);
			MongoDriverSettings settings = context.getBean(MongoDriverSettings.class);
			assertEquals("bosk", settings.database());
			assertEquals("boskCollection", settings.collection());
			assertEquals(10_000, settings.timescaleMS());
			assertEquals(MongoDriverSettings.InitialDatabaseUnavailableMode.DISCONNECT, settings.initialDatabaseUnavailableMode());
		});
	}

	@Test
	void databaseFromSpringMongoProperty() {
		runner.withPropertyValues("spring.mongodb.database=myDb").run(context ->
			assertEquals("myDb", context.getBean(MongoDriverSettings.class).database()));
	}

	@Test
	void databaseFromUri() {
		runner.withPropertyValues("spring.mongodb.uri=mongodb://localhost:27017/myUriDb").run(context ->
			assertEquals("myUriDb", context.getBean(MongoDriverSettings.class).database()));
	}

	@Test
	void customBoskPropertiesApplied() {
		runner.withPropertyValues(
			"bosk.mongodb.collection=myCollection",
			"bosk.mongodb.timescale-ms=5000",
			"bosk.mongodb.initial-database-unavailable-mode=FAIL_FAST").run(context -> {
				MongoDriverSettings settings = context.getBean(MongoDriverSettings.class);
				assertEquals("myCollection", settings.collection());
				assertEquals(5_000, settings.timescaleMS());
				assertEquals(MongoDriverSettings.InitialDatabaseUnavailableMode.FAIL_FAST, settings.initialDatabaseUnavailableMode());
			});
	}

	@Test
	void customSettingsBeanBacksOff() {
		MongoDriverSettings custom = MongoDriverSettings.builder().database("custom").build();
		runner.withBean(MongoDriverSettings.class, () -> custom).run(context -> {
			assertThat(context).hasSingleBean(MongoDriverSettings.class);
			assertEquals(custom, context.getBean(MongoDriverSettings.class));
		});
	}

	@Test
	void customSerializerBeanBacksOff() {
		BsonSerializer custom = new BsonSerializer();
		runner.withBean(BsonSerializer.class, () -> custom).run(context -> {
			assertThat(context).hasSingleBean(BsonSerializer.class);
			assertSame(custom, context.getBean(BsonSerializer.class));
		});
	}

	@Test
	void customDriverFactoryBeanBacksOff() {
		var custom = MongoDriver.factory(
			MongoClientSettings.builder().build(),
			MongoDriverSettings.builder().database("custom").build(),
			new BsonSerializer());
		runner.withBean(MongoDriver.MongoDriverFactory.class, () -> custom).run(context -> {
			assertThat(context).hasSingleBean(MongoDriver.MongoDriverFactory.class);
			assertSame(custom, context.getBean(MongoDriver.MongoDriverFactory.class));
		});
	}

	@Test
	void buildSettings_usesTheUriWhenThereIsNoSettingsBean() {
		MongoClientSettings result = MongoDriverAutoConfiguration.buildMongoClientSettings(
			"mongodb://dbhost:27017/mydb", null, List.of());
		assertEquals(List.of(new ServerAddress("dbhost", 27017)), result.getClusterSettings().getHosts());
	}

	@Test
	void buildSettings_usesTheProvidedSettingsBeanAsTheBase() {
		MongoClientSettings custom = MongoClientSettings.builder()
			.applyToClusterSettings(builder -> builder.hosts(List.of(new ServerAddress("customhost", 27018))))
			.build();
		MongoClientSettings result = MongoDriverAutoConfiguration.buildMongoClientSettings(null, custom, List.of());
		assertEquals(List.of(new ServerAddress("customhost", 27018)), result.getClusterSettings().getHosts());
	}

	@Test
	void buildSettings_appliesTheCustomizersOnTop() {
		// A customizer like Spring's Standard one carries the connection details
		MongoClientSettings result = MongoDriverAutoConfiguration.buildMongoClientSettings(
			"mongodb://dbhost:27017/mydb", null,
			List.of(builder -> builder.readPreference(ReadPreference.secondaryPreferred())));
		assertEquals(ReadPreference.secondaryPreferred(), result.getReadPreference());
	}

	@Test
	void withSpringData_theApplicationCustomizersAreApplied() {
		AtomicBoolean applied = new AtomicBoolean();
		springDataRunner
			.withBean(MongoClientSettingsBuilderCustomizer.class, () -> builder -> applied.set(true))
			.run(context -> assertTrue(applied.get(),
				"The factory must apply the application's MongoClientSettingsBuilderCustomizer beans"));
	}
}
