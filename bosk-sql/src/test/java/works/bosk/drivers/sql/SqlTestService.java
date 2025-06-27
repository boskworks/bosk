package works.bosk.drivers.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import works.bosk.drivers.state.TestEntity;
import works.bosk.exceptions.NotYetImplementedException;

import static com.fasterxml.jackson.core.JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION;
import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;

public class SqlTestService {
	record DBKey(Database database, String databaseName) {}
	private static final ConcurrentHashMap<DBKey, HikariDataSource> DATA_SOURCES = new ConcurrentHashMap<>();

	static final Path TEMP_DIR;

	static {
		try {
			TEMP_DIR = Files.createTempDirectory("bosk-test");
			TEMP_DIR.toFile().deleteOnExit();
		} catch (IOException e) {
			throw new NotYetImplementedException(e);
		}
	}

	public enum Database {
		MYSQL(testcontainers("mysql:8.0.36", "/var/lib/mysql")),
		POSTGRES(testcontainers("postgresql:17", "/var/lib/postgresql/data")),
		SQLITE(dbName -> "jdbc:sqlite:" + TEMP_DIR.resolve(dbName + ".db")),
		;

		final Function<String, String> url;

		Database(Function<String, String> url) {
			this.url = url;
		}

		public HikariDataSource dataSourceFor(String databaseName) {
			return DATA_SOURCES.computeIfAbsent(new DBKey(this, databaseName), SqlTestService::newHikariDataSource);
		}
	}

	private static HikariDataSource newHikariDataSource(DBKey key) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(key.database().url.apply(key.databaseName()));
		config.setAutoCommit(false);
		return new HikariDataSource(config);
	}

	private static Function<String, String> testcontainers(String image, String dataDir) {
		return dbName -> "jdbc:tc:" + image
			+ ":///" + dbName
			+ "?TC_DAEMON=true"
			+ "&TC_TMPFS=" + dataDir + ":rw"
			;
	}

	public static SqlDriverImpl.SqlDriverFactory<TestEntity> sqlDriverFactory(SqlDriverSettings settings, HikariDataSource dataSource) {
		return SqlDriver.factory(
			settings, dataSource::getConnection,
			(b, m) -> m
				.enable(INDENT_OUTPUT)
				.enable(INCLUDE_SOURCE_IN_LOCATION)
		);
	}

}
