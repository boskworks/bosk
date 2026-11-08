package works.bosk.drivers.mongo.internal;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.CatalogReference;
import works.bosk.SideTable;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Replacing a whole entry that itself contains a graft point writes the entry's
 * document as the transaction's topmost write, plus one document per sub-part.
 * Replacing an existing entry leaves the container untouched; re-creating a
 * deleted entry also writes the container's stub.
 */
public class WholeDocumentReplacementTest {

	@Nested
	@ReplayLogsOnFailure
	@InjectFields
	class EntryWithSubParts extends AbstractMongoDriverTest {
		@InjectorMethod
		static Stream<ParameterSet> parameterSets() {
			return Stream.of(new ParameterSet(
				"WholeDocumentReplacementTest",
				MongoDriverSettings.builder()
					.preferredDatabaseFormat(PandoFormat.withGraftPoints("/catalog", "/catalog/-x-/sideTable"))
					.timescaleMS(LONG_TIMESCALE)
					.database("WholeDocumentReplacementTest")));
		}

		@AfterEach
		void resetProbes() {
			MainDriver.resetProbes();
		}

		@Test
		void replacementWritesEntryAndSubPart_butNotContainer() throws Exception {
			ErrorRecordingChangeListener.ErrorRecorder errors = new ErrorRecordingChangeListener.ErrorRecorder();
			RecordingWrites writes = new RecordingWrites();
			MainDriver.setProbes(TestProbes.noop()
				.withWriteInterceptor(writes)
				.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errors, downstream)));
			Bosk<TestEntity> bosk = newBosk();
			Refs refs = bosk.buildReferences(Refs.class);
			bosk.driver().flush();
			TestEntity replacement = replacement(refs);
			writes.clear();

			bosk.driver().submitReplacement(refs.catalog().then(entity123), replacement);
			bosk.driver().flush();

			errors.assertAllClear("while replacing the entry");
			assertEquals(Set.of("|catalog|123", "|catalog|123|sideTable|valuesById|124"),
				Set.copyOf(writes.documentIds()),
				"The replacement must write the entry's document and its sub-part, and nothing else");
			assertFalse(writes.documentIds().contains("|"),
				"The root document must not be written");
			try (var _ = bosk.readSession()) {
				assertEquals(replacement, refs.catalog().then(entity123).value(),
					"The replacement must be applied downstream");
			}
		}

		@Test
		void replacementAfterDeletionWritesEntryAndSubPart_butNotContainer() throws Exception {
			ErrorRecordingChangeListener.ErrorRecorder errors = new ErrorRecordingChangeListener.ErrorRecorder();
			RecordingWrites writes = new RecordingWrites();
			MainDriver.setProbes(TestProbes.noop()
				.withWriteInterceptor(writes)
				.withListenerFactory(downstream -> new ErrorRecordingChangeListener(errors, downstream)));
			Bosk<TestEntity> bosk = newBosk();
			Refs refs = bosk.buildReferences(Refs.class);
			bosk.driver().flush();
			TestEntity replacement = replacement(refs);

			bosk.driver().submitDeletion(refs.catalog().then(entity123));
			bosk.driver().flush();
			writes.clear();

			bosk.driver().submitReplacement(refs.catalog().then(entity123), replacement);
			bosk.driver().flush();

			errors.assertAllClear("while re-creating the entry");
			assertEquals(Set.of("|catalog|123", "|catalog|123|sideTable|valuesById|124", "|"),
				Set.copyOf(writes.documentIds()),
				"Re-creating the entry must write its document, its sub-part, and the container's stub");
			try (var _ = bosk.readSession()) {
				assertEquals(replacement, refs.catalog().then(entity123).value(),
					"The re-created entry must be applied downstream");
			}
		}

		private TestEntity replacement(Refs refs) {
			CatalogReference<TestEntity> childCatalog = refs.childCatalog(entity123);
			return TestEntity.empty(entity123, childCatalog)
				.withSideTable(SideTable.of(childCatalog, entity124, TestEntity.empty(entity124, childCatalog)));
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
