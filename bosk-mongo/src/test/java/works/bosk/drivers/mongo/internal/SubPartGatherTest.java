package works.bosk.drivers.mongo.internal;

import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Sub-parts written along with a field are gathered into a synthetic part whose
 * document ID must be an absolute BSON path, even when the topmost document is
 * a graft-point entry rather than the root.
 */
public class SubPartGatherTest {

	@Nested
	@ReplayLogsOnFailure
	@InjectFields
	class NonRootTopmost extends AbstractMongoDriverTest {
		@InjectorMethod
		static Stream<ParameterSet> parameterSets() {
			return Stream.of(new ParameterSet(
				"SubPartGatherTest",
				MongoDriverSettings.builder()
					.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog", "/catalog/-x-/catalog"))
					.timescaleMS(LONG_TIMESCALE)
					.database("SubPartGatherTest")));
		}

		@AfterEach
		void resetProbes() {
			MainDriver.resetProbes();
		}

		@Test
		void replacingAFieldWithSubParts_gathersIntoTheTopmostDocument() throws Exception {
			ErrorRecordingChangeListener.ErrorRecorder errors = new ErrorRecordingChangeListener.ErrorRecorder();
			MainDriver.setProbes(TestProbes.noop()
				.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errors, downstream)));
			Bosk<TestEntity> bosk = newBosk();
			Refs refs = bosk.buildReferences(Refs.class);
			bosk.driver().flush();

			CatalogReference<TestEntity> childCatalog = refs.childCatalog(entity123);
			Catalog<TestEntity> replacement = Catalog.of(TestEntity.empty(entity124, childCatalog));
			bosk.driver().submitReplacement(childCatalog, replacement);
			bosk.driver().flush();

			errors.assertAllClear("while gathering sub-parts");
			try (var _ = bosk.readSession()) {
				assertEquals(replacement, childCatalog.value(),
					"The replacement must be applied downstream");
			}
		}

		private Bosk<TestEntity> newBosk() throws InvalidTypeException {
			return new Bosk<>(
				boskName(),
				TestEntity.class,
				AbstractMongoDriverTest::initialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		}
	}
}
