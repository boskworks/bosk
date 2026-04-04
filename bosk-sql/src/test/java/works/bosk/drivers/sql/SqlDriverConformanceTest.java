package works.bosk.drivers.sql;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.junit.jupiter.Testcontainers;
import works.bosk.drivers.sql.SqlTestService.Database;
import works.bosk.drivers.sql.schema.Schema;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;
import works.bosk.testing.drivers.PolyfillDriverConformanceTest;

import static works.bosk.drivers.sql.SqlTestService.sqlDriverFactory;

@InjectFields
@InjectFrom(DatabaseInjector.class)
@Testcontainers
class SqlDriverConformanceTest extends PolyfillDriverConformanceTest {
	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	@Injected Database database;
	private final AtomicInteger dbCounter = new AtomicInteger(0);
	private SqlDriverSettings settings;
	private HikariDataSource dataSource;

	@BeforeAll
	static void validateSmokeTestDatabases() {
		// Fail this test promptly if we can't connect to the smoke test databases,
		// instead of painstakingly hitting this error on every single test.
		for (Database database : Database.values()) {
			try (var ds = database.dataSourceFor("smoke-test")) {
				ds.validate();
			}
		}
	}

	@BeforeEach
	void setupDriverFactory() {
		settings = new SqlDriverSettings(
			50, 100
		);
		String databaseName = SqlDriverConformanceTest.class.getSimpleName()
			+ dbCounter.incrementAndGet();
		dataSource = database.dataSourceFor(databaseName);
		driverFactory = (boskInfo, downstream) -> {
			var driver = sqlDriverFactory(settings, dataSource).build(boskInfo, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};
	}

	@AfterEach
	void runTearDown() throws SQLException {
		tearDownActions.forEach(Runnable::run);
		cleanupTables();
	}

	private void cleanupTables() throws SQLException {
		try (
			var c = dataSource.getConnection()
		) {
			new Schema().dropTables(c);
		}
	}

}
