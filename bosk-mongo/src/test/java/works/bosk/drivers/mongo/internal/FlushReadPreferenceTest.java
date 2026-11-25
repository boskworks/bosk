package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Entity;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.BoskLogFilter;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Flush needs the deployment's latest data, so its reads must not inherit a
 * user's read preference: a read served by a lagging secondary would make flush
 * return before the caller's own update had been applied.
 * <p>
 * The commands are the only observable here. Asserting that they carry
 * "primary" would be misleading, because the test client connects directly to a
 * single replica-set member, where the driver declares any primary read as
 * "primaryPreferred". So the test asserts the property we actually care about:
 * no flush command carries the client's configured preference.
 */
@InjectFields
@ReplayLogsOnFailure
class FlushReadPreferenceTest extends AbstractMongoDriverTest {
	private final List<String> epochReadModes = new CopyOnWriteArrayList<>();
	private final List<String> probeModes = new CopyOnWriteArrayList<>();

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.standardDriverSettings();
	}

	@Test
	void flushDoesNotInheritClientReadPreference() throws InvalidTypeException, IOException, InterruptedException {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		// Ignore everything the driver did while initializing.
		epochReadModes.clear();
		probeModes.clear();

		bosk.driver().flush();

		assertFalse(epochReadModes.isEmpty(), "Flush must read the epoch");
		assertFalse(probeModes.isEmpty(), "Flush must probe the change stream");
		assertAllGoToPrimary(epochReadModes, "Epoch reads");
		assertAllGoToPrimary(probeModes, "Change-stream probes");
	}

	/**
	 * A primary read on this direct connection is rendered either as no
	 * {@code $readPreference} at all or as "primaryPreferred", depending on how
	 * the driver renders it, so either spelling is acceptable.
	 */
	private static void assertAllGoToPrimary(List<String> modes, String description) {
		for (String mode : modes) {
			assertTrue(List.of(ReadPreference.primary().getName(), ReadPreference.primaryPreferred().getName()).contains(mode),
				description + " must go to the primary, but one carried " + mode);
		}
	}

	@Override
	protected <E extends Entity> DriverFactory<E> createDriverFactory(BoskLogFilter.LogController logController, TestInfo testInfo) {
		CommandListener recorder = new CommandListener() {
			@Override
			public void commandStarted(CommandStartedEvent event) {
				// The command document is backed by a buffer that is only valid
				// during this callback, so extract what we need right away.
				BsonDocument command = event.getCommand();
				// A collection-level read names its collection; a database-level
				// command like the snapshot's $documents aggregate does not.
				BsonValue target = command.get(event.getCommandName());
				if (target != null
					&& target.isString()
					&& driverSettings.collection().equals(target.asString().getValue())) {
					if (event.getCommandName().equals("find")) {
						epochReadModes.add(modeOf(command));
					} else if (event.getCommandName().equals("aggregate") && isFlushProbe(command)) {
						probeModes.add(modeOf(command));
					}
				}
			}
		};
		MongoClientSettings clientSettings = MongoClientSettings
			.builder(mongoService.clientSettings(testInfo))
			.readPreference(ReadPreference.secondaryPreferred())
			.addCommandListener(recorder)
			.build();
		DriverFactory<E> mongoDriverFactory = (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<E>factory(
				clientSettings,
				driverSettings,
				new BsonSerializer()
			).build(boskInfo, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};
		return DriverStack.of(
			BoskLogFilter.withController(logController),
			mongoDriverFactory
		);
	}

	private static String modeOf(BsonDocument command) {
		BsonDocument readPreference = command.getDocument("$readPreference", null);
		return readPreference == null
			? ReadPreference.primary().getName()
			: readPreference.getString("mode").getValue();
	}

	/**
	 * Flush's probes add stages after {@code $changeStream}; the receiver's own
	 * stream does not, so this distinguishes them even if the receiver happens to
	 * reopen its stream during the test.
	 */
	private static boolean isFlushProbe(BsonDocument command) {
		return command.getArray("pipeline", new BsonArray()).size() > 1;
	}
}
