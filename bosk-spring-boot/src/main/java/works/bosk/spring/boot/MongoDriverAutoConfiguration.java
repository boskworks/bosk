package works.bosk.spring.boot;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;

/**
 * Auto-configures the beans needed to build a MongoDB-backed bosk when {@code bosk-mongo}
 * is on the classpath: a {@link MongoDriverSettings}, a {@link BsonSerializer}, and a
 * {@link MongoDriver.MongoDriverFactory}.
 * <p>
 * The connection is composed the same way Spring's {@code MongoAutoConfiguration}
 * composes its own client: the {@code MongoClientSettings} bean (Spring's, or the
 * application's own) is taken as the base, and every {@code MongoClientSettingsBuilderCustomizer}
 * bean is applied on top. This honors both the {@code spring.mongodb.uri} and the
 * host/port/credentials/ssl property forms, plus any application customizers, so an
 * application that already connects to MongoDB via Spring Data picks up the same
 * connection for bosk with no additional configuration. The {@code bosk.mongodb.*}
 * properties customize bosk-specific settings, and each bean backs off if the
 * application defines its own.
 */
@AutoConfiguration
@ConditionalOnClass(MongoDriver.class)
@EnableConfigurationProperties(BoskMongoProperties.class)
@SuppressWarnings("exports") // because this public API references the optional, non-transitive bosk-mongo module
public class MongoDriverAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(BsonSerializer.class)
	BsonSerializer bsonSerializer() {
		return new BsonSerializer();
	}

	@Bean
	@ConditionalOnMissingBean(MongoDriverSettings.class)
	MongoDriverSettings mongoDriverSettings(Environment environment, BoskMongoProperties properties) {
		MongoDriverSettings.MongoDriverSettingsBuilder builder = MongoDriverSettings.builder()
			.database(databaseName(environment));
		if (properties.timescaleMS() != null) {
			builder.timescaleMS(properties.timescaleMS());
		}
		if (properties.collection() != null) {
			builder.collection(properties.collection());
		}
		if (properties.initialDatabaseUnavailableMode() != null) {
			builder.initialDatabaseUnavailableMode(properties.initialDatabaseUnavailableMode());
		}
		return builder.build();
	}

	@Bean
	@ConditionalOnMissingBean(MongoDriver.MongoDriverFactory.class)
	MongoDriver.MongoDriverFactory<?> mongoDriverFactory(
		Environment environment,
		MongoDriverSettings driverSettings,
		BsonSerializer bsonSerializer,
		ObjectProvider<MongoClientSettings> settings,
		ObjectProvider<List<Consumer<MongoClientSettings.Builder>>> settingsCustomizers
	) {
		MongoClientSettings clientSettings = buildMongoClientSettings(
			environment.getProperty("spring.mongodb.uri"),
			settings.getIfAvailable(),
			settingsCustomizers.getIfAvailable(List::of));
		return MongoDriver.factory(clientSettings, driverSettings, bsonSerializer);
	}

	/**
	 * Builds the settings bosk's clients use: the {@code MongoClientSettings} bean if
	 * there is one (the application's own, or Spring's), otherwise the
	 * {@code spring.mongodb.uri} connection string, with every
	 * {@code MongoClientSettingsBuilderCustomizer} applied on top. This mirrors how
	 * Spring composes its own {@code MongoClient}, so bosk honors both the URI and the
	 * host/port/credentials/ssl property forms, plus any application customizers.
	 */
	static MongoClientSettings buildMongoClientSettings(
		@Nullable String uri,
		@Nullable MongoClientSettings base,
		List<Consumer<MongoClientSettings.Builder>> settingsCustomizers
	) {
		MongoClientSettings.Builder builder;
		if (base != null) {
			builder = MongoClientSettings.builder(base);
		} else {
			builder = MongoClientSettings.builder();
			if (hasText(uri)) {
				builder.applyConnectionString(new ConnectionString(uri));
			}
		}
		for (Consumer<MongoClientSettings.Builder> customizer : settingsCustomizers) {
			customizer.accept(builder);
		}
		return builder.build();
	}

	/**
	 * Adapts the application's {@link MongoClientSettingsBuilderCustomizer} beans into
	 * neutral consumers, so the factory bean doesn't need to reference the optional
	 * {@code spring-boot-mongodb} types when that module is absent.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MongoClientSettingsBuilderCustomizer.class)
	@SuppressWarnings("exports") // because this configuration references the optional, non-transitive spring-boot-mongodb module
	static class MongoSettingsCustomizerConfiguration {

		@Bean
		List<Consumer<MongoClientSettings.Builder>> mongoSettingsCustomizers(
			List<MongoClientSettingsBuilderCustomizer> customizers
		) {
			return customizers.stream()
				.<Consumer<MongoClientSettings.Builder>>map(customizer -> customizer::customize)
				.toList();
		}
	}

	/**
	 * The database in which the bosk state is stored: the {@code spring.mongodb.database}
	 * property if set, otherwise the database named in the {@code spring.mongodb.uri}
	 * connection string, otherwise {@code bosk}. The {@code bosk} fallback applies only
	 * when the application has configured no database name at all, which is not the
	 * normal production setup; it keeps bosk's state in its own database rather than
	 * guessing the application's default (Spring Data's is {@code test}).
	 */
	private static String databaseName(Environment environment) {
		String database = environment.getProperty("spring.mongodb.database");
		if (hasText(database)) {
			return database;
		}
		String uri = environment.getProperty("spring.mongodb.uri");
		if (hasText(uri)) {
			String fromUri = new ConnectionString(uri).getDatabase();
			if (hasText(fromUri)) {
				return fromUri;
			}
		}
		return "bosk";
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
