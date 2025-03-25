package works.bosk.drivers.mongo;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import works.bosk.DriverStack;
import works.bosk.drivers.HanoiTest;
import works.bosk.drivers.mongo.bson.BsonSerializer;
import works.bosk.junit.ParametersByName;
import works.bosk.junit.Slow;

import static works.bosk.drivers.mongo.MainDriver.COLLECTION_NAME;

@Slow
public class MongoDriverHanoiTest extends HanoiTest {
	private static MongoService mongoService;
	private final Queue<Runnable> shutdownOperations = new ConcurrentLinkedDeque<>();

	@ParametersByName
	public MongoDriverHanoiTest(TestParameters.ParameterSet parameters, TestInfo testInfo) {
		MongoDriverSettings settings = parameters.driverSettingsBuilder().build();
		this.driverFactory = DriverStack.of(
			(_,d) -> { shutdownOperations.add(((MongoDriver)d)::close); return d;},
			MongoDriver.factory(
				mongoService.clientSettings(testInfo),
				settings,
				new BsonSerializer()
			)
		);
		mongoService.client()
			.getDatabase(settings.database())
			.getCollection(COLLECTION_NAME)
			.drop();
	}

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@BeforeEach
	void logStart(TestInfo testInfo) {
		AbstractMongoDriverTest.logTest("/=== Start", testInfo);
	}

	@AfterEach
	void logDone(TestInfo testInfo) {
		shutdownOperations.forEach(Runnable::run);
		shutdownOperations.clear();
		AbstractMongoDriverTest.logTest("\\=== Done", testInfo);
	}

	@SuppressWarnings("unused")
	static Stream<TestParameters.ParameterSet> parameters() {
		return TestParameters.driverSettings(
			Stream.of(
				PandoFormat.oneBigDocument(),
				PandoFormat.withGraftPoints("/puzzles"),
				PandoFormat.withGraftPoints("/puzzles/-puzzle-/towers"),
				PandoFormat.withGraftPoints("/puzzles", "/puzzles/-puzzle-/towers/-tower-/discs"),
				MongoDriverSettings.DatabaseFormat.SEQUOIA
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		);
	}

}
