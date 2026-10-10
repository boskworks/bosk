package works.bosk.drivers.sql;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.StateTreeNode;
import works.bosk.drivers.sql.SqlTestService.Database;
import works.bosk.drivers.sql.schema.Schema;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;
import works.bosk.util.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@InjectFields
@InjectFrom({DatabaseInjector.class})
class SqlDriverParameterizedRootTest {
	@Injected Database database;

	HikariDataSource dataSource;
	SqlDriverSettings settings;
	final List<SqlDriver> drivers = new ArrayList<>();

	@BeforeEach
	void setup() {
		settings = new SqlDriverSettings(1000, 100);
		dataSource = database.dataSourceFor(SqlDriverParameterizedRootTest.class.getSimpleName());
	}

	@AfterEach
	void tearDown() throws SQLException {
		for (SqlDriver driver : drivers) {
			driver.close();
		}
		try (var connection = dataSource.getConnection()) {
			new Schema().dropTables(connection);
		}
	}

	@Test
	void parameterizedRootSurvivesReload() throws InvalidTypeException, IOException, InterruptedException {
		Type rootType = Types.parameterizedType(GenericNode.class, Point.class);
		DriverFactory<GenericNode<Point>> factory = (boskInfo, downstream) -> {
			SqlDriver driver = SqlDriver.<GenericNode<Point>>factory(settings, dataSource::getConnection, (_, mapper) -> mapper).build(boskInfo, downstream);
			drivers.add(driver);
			return driver;
		};

		new Bosk<>(
			"writer",
			rootType,
			_ -> new GenericNode<>(new Point(1, 2)),
			BoskConfig.<GenericNode<Point>>builder().driverFactory(factory).build());

		var reader = new Bosk<>(
			"reader",
			rootType,
			_ -> new GenericNode<>(new Point(9, 9)),
			BoskConfig.<GenericNode<Point>>builder().driverFactory(factory).build());

		try (var _ = reader.readSession()) {
			assertEquals(new GenericNode<>(new Point(1, 2)), reader.rootReference().value());
		}
	}

	public record GenericNode<T>(T value) implements StateTreeNode { }

	public record Point(int x, int y) implements StateTreeNode { }
}
