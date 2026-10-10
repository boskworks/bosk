package works.bosk.drivers.mongo.internal;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.util.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.drivers.mongo.internal.TestParameters.EventTiming.NORMAL;

/**
 * The BSON serializer handles parameterized types (see {@link works.bosk.drivers.mongo.BsonSerializerTest}),
 * but the driver is what supplies the serializer with the target's declared type.
 * A driver that used the value's runtime class would serialize a parameterized node's
 * components with their erased types, which no serializer test would catch. This test
 * pins that the Mongo driver writes and reloads a parameterized root, both as an
 * initial state and as a replacement.
 */
@InjectFields
public class MongoDriverParameterizedRootTest extends AbstractMongoDriverTest {

	TestInfo testInfo;

	@BeforeEach
	void captureTestInfo(TestInfo testInfo) {
		this.testInfo = testInfo;
	}

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(MongoDriverSettings.DatabaseFormat.SEQUOIA, PandoFormat.oneBigDocument()),
			Stream.of(NORMAL));
	}

	public record GenericNode<T>(T value) implements StateTreeNode { }

	public record Point(int x, int y) implements StateTreeNode { }

	@Test
	void parameterizedRootRoundTrips() throws InvalidTypeException, IOException, InterruptedException {
		Type rootType = Types.parameterizedType(GenericNode.class, Point.class);
		DriverFactory<GenericNode<Point>> mongoDriverFactory = (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<GenericNode<Point>>factory(
				mongoService.clientSettings(testInfo),
				driverSettings,
				new BsonSerializer()
			).build(boskInfo, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};

		Bosk<GenericNode<Point>> writer = new Bosk<>(
			"writer",
			rootType,
			_ -> new GenericNode<>(new Point(1, 2)),
			BoskConfig.<GenericNode<Point>>builder().driverFactory(mongoDriverFactory).build());
		assertReloads(rootType, mongoDriverFactory, new GenericNode<>(new Point(1, 2)));

		writer.driver().submitReplacement(writer.rootReference(), new GenericNode<>(new Point(3, 4)));
		writer.driver().flush();
		assertReloads(rootType, mongoDriverFactory, new GenericNode<>(new Point(3, 4)));
	}

	/**
	 * @param expected the state in the database; a fresh Bosk is given an obviously
	 * different fallback, so a reader that fails to load the database state shows up
	 * as a mismatched assertion rather than passing by accident
	 */
	private void assertReloads(Type rootType, DriverFactory<GenericNode<Point>> driverFactory, GenericNode<Point> expected) throws InvalidTypeException, IOException, InterruptedException {
		Bosk<GenericNode<Point>> reader = new Bosk<>(
			"reader",
			rootType,
			_ -> new GenericNode<>(new Point(-1, -1)),
			BoskConfig.<GenericNode<Point>>builder().driverFactory(driverFactory).build());
		try (var _ = reader.readSession()) {
			assertEquals(expected, reader.rootReference().value());
		}
	}
}
