package works.bosk.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode;

import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.DISCONNECT;
import static works.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.FAIL_FAST;

/**
 * Configuration for the auto-configured MongoDB driver.
 * <p>
 * All properties are optional: when a property is unset, the auto-configuration leaves
 * the corresponding {@link MongoDriverSettings} at its own default, so bosk's behaviour
 * never diverges from its defaults unless the application says so. The connection and
 * database are taken from the application's existing {@code spring.mongodb.*}
 * properties, so an application that already connects to MongoDB via Spring Data picks
 * up the same connection and database for bosk with no additional configuration.
 *
 * @param timescaleMS the responsiveness scale, in milliseconds; see
 * {@link MongoDriverSettings}; defaults to bosk's own default of 10 seconds
 * @param collection the collection in which the bosk state is stored within the
 * database; defaults to {@code boskCollection}, so a bosk can coexist in a database
 * alongside the application's own collections
 * @param initialDatabaseUnavailableMode how to behave if the database state can't be
 * loaded during initialization; defaults to bosk's own default of {@link DISCONNECT},
 * but you may prefer {@link FAIL_FAST} during development to get helpful errors when the
 * database is misconfigured
 */
@ConfigurationProperties("bosk.mongodb")
@SuppressWarnings("exports") // for InitialDatabaseUnavailable
public record BoskMongoProperties(
	Integer timescaleMS,
	String collection,
	InitialDatabaseUnavailableMode initialDatabaseUnavailableMode
) {}
