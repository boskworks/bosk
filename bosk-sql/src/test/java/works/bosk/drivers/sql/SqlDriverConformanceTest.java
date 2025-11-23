package works.bosk.drivers.sql;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Parameter;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.junit.jupiter.Testcontainers;
import works.bosk.drivers.sql.SqlTestService.Database;
import works.bosk.drivers.sql.schema.Schema;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.ParameterInjector;
import works.bosk.testing.drivers.SharedDriverConformanceTest;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static works.bosk.drivers.sql.SqlTestService.Database.MYSQL;
import static works.bosk.drivers.sql.SqlTestService.Database.POSTGRES;
import static works.bosk.drivers.sql.SqlTestService.Database.SQLITE;
import static works.bosk.drivers.sql.SqlTestService.sqlDriverFactory;

@Testcontainers
@InjectFrom({
	SqlDriverConformanceTest.DatabaseInjector.class
})
class SqlDriverConformanceTest extends SharedDriverConformanceTest {
	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	private final Database database;
	private final AtomicInteger dbCounter = new AtomicInteger(0);
	private SqlDriverSettings settings;
	private HikariDataSource dataSource;

	private static final Set<Database> SMOKE_TEST_DBS = EnumSet.of(POSTGRES);

	SqlDriverConformanceTest(Database database, TestInfo testInfo) {
		this.database = database;
		assumeTrue(SMOKE_TEST_DBS.contains(database)
			|| testInfo.getTags().contains("slow"));
	}

	record DatabaseInjector() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType() == Database.class;
		}

		@Override
		public List<Database> values() {
			return List.of(POSTGRES, MYSQL, SQLITE);
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
