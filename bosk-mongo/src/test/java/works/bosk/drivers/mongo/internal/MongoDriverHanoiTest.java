package works.bosk.drivers.mongo.internal;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import works.bosk.DriverStack;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.testing.drivers.HanoiTest;
import works.bosk.testing.junit.Slow;

import static works.bosk.drivers.mongo.internal.MainDriver.COLLECTION_NAME;

@Slow
@ParameterizedClass
@MethodSource("parameterSets")
public class MongoDriverHanoiTest extends HanoiTest {
	private static MongoService mongoService;
	private final Queue<Runnable> shutdownOperations = new ConcurrentLinkedDeque<>();

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

	static List<TestParameters.ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
				Stream.of(
					PandoFormat.oneBigDocument(),
					PandoFormat.withGraftPoints("/puzzles"),
					PandoFormat.withGraftPoints("/puzzles/-puzzle-/towers"),
					PandoFormat.withGraftPoints("/puzzles", "/puzzles/-puzzle-/towers/-tower-/discs"),
					MongoDriverSettings.DatabaseFormat.SEQUOIA
				),
				Stream.of(TestParameters.EventTiming.NORMAL))
			.toList();
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

}
