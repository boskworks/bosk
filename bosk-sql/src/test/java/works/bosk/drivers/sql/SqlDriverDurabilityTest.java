package works.bosk.drivers.sql;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import works.bosk.Bosk;
import works.bosk.DriverStack;
import works.bosk.drivers.sql.SqlTestService.Database;
import works.bosk.drivers.sql.schema.Schema;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.logback.BoskLogFilter;
import works.bosk.testing.drivers.AbstractDriverTest;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.junit.ParametersByName;

import static ch.qos.logback.classic.Level.ERROR;
import static org.jooq.impl.DSL.using;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.drivers.sql.SqlTestService.Database.POSTGRES;
import static works.bosk.drivers.sql.SqlTestService.Database.SQLITE;
import static works.bosk.drivers.sql.SqlTestService.sqlDriverFactory;
import static works.bosk.testing.BoskTestUtils.boskName;

@Testcontainers
public class SqlDriverDurabilityTest extends AbstractDriverTest {
	private final Database database;
	SqlDriverSettings settings;
	HikariDataSource dataSource;
	AtomicInteger dbCounter = new AtomicInteger(0);
	private BoskLogFilter.LogController logController;

	@ParametersByName
	SqlDriverDurabilityTest(Database database) {
		this.database = database;
	}

	@SuppressWarnings("unused")
	static Stream<Database> database() {
		return Stream.of(POSTGRES, SQLITE);
	}

	@BeforeEach
	void initializeSettings() {
		settings = new SqlDriverSettings(1000, 100);
		dataSource = database.dataSourceFor(SqlDriverDurabilityTest.class.getSimpleName() + dbCounter.incrementAndGet());
		logController = new BoskLogFilter.LogController();
	}

	@ParametersByName
	void tablesDropped_recovers() throws SQLException, IOException, InterruptedException {
		logController.setLogging(ERROR, SqlDriverImpl.class); // We're expecting disruption here

		LOGGER.debug("Initialize database");
		var factory = DriverStack.of(
			BoskLogFilter.withController(logController),
			sqlDriverFactory(settings, dataSource)
		);

		// Note that we can't use DriverStateVerifier here because this test
		// depends on receiving a new state from another bosk, and DriverStateVerifier
		// will report that as an unexpected update. DriverStateVerifier objects
		// to spontaneous state changes, even though they are valid in a setup with
		// multiple bosks sharing a database.
		//
		bosk = new Bosk<>(boskName("tablesDropped", 1), TestEntity.class, AbstractDriverTest::initialRoot, factory, Bosk.simpleRegistrar());
		driver = bosk.driver();

		var schema = new Schema();
		LOGGER.debug("Drop tables");
		try (var c = dataSource.getConnection()) {
			using(c)
				.dropTable(schema.BOSK)
				.execute();

			using(c)
				.dropTable(schema.CHANGES)
				.execute();

			c.commit();
		}

		assertThrows(FlushFailureException.class, () -> bosk.driver().flush());

		LOGGER.debug("Use another bosk to recreate the database");
		var fixer = new Bosk<>("fixer", TestEntity.class, SqlDriverDurabilityTest::differentInitialRoot, factory, Bosk.simpleRegistrar());

		LOGGER.debug("State should be restored");

		bosk.driver().flush();

		TestEntity actual;
		try (var __ = bosk.readContext()) {
			actual = bosk.rootReference().value();
		}

		TestEntity expected;
		try (var __ = fixer.readContext()) {
			expected = fixer.rootReference().value();
		}

		assertEquals(expected, actual);
	}

	private static @NotNull TestEntity differentInitialRoot(Bosk<TestEntity> b) throws InvalidTypeException {
		return AbstractDriverTest.initialRoot(b).withString("Different");
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(SqlDriverDurabilityTest.class);
}
