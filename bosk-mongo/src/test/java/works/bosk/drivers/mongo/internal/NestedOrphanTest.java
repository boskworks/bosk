package works.bosk.drivers.mongo.internal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A HASTY deletion can leave a whole subtree of orphan documents behind. A
 * conditional creation inside that subtree must not treat the orphans as an
 * existing parent, and must not write anything.
 * <p>
 * The two-level case makes the parent and child entries separate documents.
 * The three-level case makes the grandchild a document too, so the membership
 * check has to walk more than one ancestor.
 */
public class NestedOrphanTest {

	@Nested
	@ReplayLogsOnFailure
	@InjectFields
	class TwoLevels extends Base {
		@InjectorMethod
		static Stream<ParameterSet> parameterSets() {
			return Stream.of(new ParameterSet(
				"NestedOrphanTestTwoLevels",
				MongoDriverSettings.builder()
					.preferredDatabaseFormat(PandoFormat.withGraftPoints(
						"/catalog",
						"/catalog/-x-/catalog"))
					.timescaleMS(LONG_TIMESCALE)
					.database("NestedOrphanTestTwoLevels")));
		}

		@Test
		void conditionalCreationUnderDeletedParent_doesNothing() throws Exception {
			Identifier parentID = Identifier.from("e0");
			Identifier childID = Identifier.from("c1");
			AtomicInteger writeCount = new AtomicInteger();
			Bosk<TestEntity> bosk = newBosk(writeCount, b -> {
				Refs refs = b.buildReferences(Refs.class);
				CatalogReference<TestEntity> parentCatalog = refs.childCatalog(parentID);
				Reference<TestEntity> child = parentCatalog.then(childID);
				TestEntity childValue = TestEntity.empty(childID,
					child.thenCatalog(TestEntity.class, TestEntity.Fields.catalog));
				TestEntity parent = TestEntity.empty(parentID, parentCatalog)
					.withCatalog(Catalog.of(childValue));
				return initialRootWithEmptyCatalog(b).withCatalog(Catalog.of(parent));
			});
			Refs refs = bosk.buildReferences(Refs.class);
			bosk.driver().flush();

			Reference<TestEntity> parent = refs.catalog().then(parentID);
			Reference<TestEntity> child = refs.childCatalog(parentID).then(childID);
			bosk.driver().submitDeletion(child);
			bosk.driver().submitDeletion(parent);
			bosk.driver().flush();

			writeCount.set(0);
			bosk.driver().submitConditionalCreation(child, TestEntity.empty(childID,
				child.thenCatalog(TestEntity.class, TestEntity.Fields.catalog)));
			bosk.driver().flush();

			assertEquals(0, writeCount.get(),
				"Conditional creation under a deleted parent must not write");
			try (var _ = bosk.readSession()) {
				assertNull(parent.valueIfExists(), "The parent must remain absent");
				assertNull(child.valueIfExists(), "The child must remain absent");
			}
		}
	}

	@Nested
	@ReplayLogsOnFailure
	@InjectFields
	class ThreeLevels extends Base {
		@InjectorMethod
		static Stream<ParameterSet> parameterSets() {
			return Stream.of(new ParameterSet(
				"NestedOrphanTestThreeLevels",
				MongoDriverSettings.builder()
					.preferredDatabaseFormat(PandoFormat.withGraftPoints(
						"/catalog",
						"/catalog/-x-/catalog",
						"/catalog/-x-/catalog/-y-/catalog"))
					.timescaleMS(LONG_TIMESCALE)
					.database("NestedOrphanTestThreeLevels")));
		}

		@Test
		void conditionalCreationUnderDeletedGrandparent_doesNothing() throws Exception {
			Identifier grandparentID = Identifier.from("e0");
			Identifier parentID = Identifier.from("c1");
			Identifier childID = Identifier.from("g2");
			AtomicInteger writeCount = new AtomicInteger();
			Bosk<TestEntity> bosk = newBosk(writeCount, b -> {
				Refs refs = b.buildReferences(Refs.class);
				CatalogReference<TestEntity> grandparentCatalog = refs.childCatalog(grandparentID);
				Reference<TestEntity> parent = grandparentCatalog.then(parentID);
				CatalogReference<TestEntity> parentCatalog = parent.thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
				Reference<TestEntity> child = parentCatalog.then(childID);
				TestEntity childValue = TestEntity.empty(childID,
					child.thenCatalog(TestEntity.class, TestEntity.Fields.catalog));
				TestEntity parentValue = TestEntity.empty(parentID, parentCatalog)
					.withCatalog(Catalog.of(childValue));
				TestEntity grandparentValue = TestEntity.empty(grandparentID, grandparentCatalog)
					.withCatalog(Catalog.of(parentValue));
				return initialRootWithEmptyCatalog(b).withCatalog(Catalog.of(grandparentValue));
			});
			Refs refs = bosk.buildReferences(Refs.class);
			bosk.driver().flush();

			Reference<TestEntity> grandparent = refs.catalog().then(grandparentID);
			CatalogReference<TestEntity> parentCatalog = refs.childCatalog(grandparentID)
				.then(parentID).thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
			Reference<TestEntity> parent = refs.childCatalog(grandparentID).then(parentID);
			Reference<TestEntity> child = parentCatalog.then(childID);
			bosk.driver().submitDeletion(child);
			bosk.driver().submitDeletion(parent);
			bosk.driver().submitDeletion(grandparent);
			bosk.driver().flush();

			writeCount.set(0);
			bosk.driver().submitConditionalCreation(child, TestEntity.empty(childID,
				child.thenCatalog(TestEntity.class, TestEntity.Fields.catalog)));
			bosk.driver().flush();

			assertEquals(0, writeCount.get(),
				"Conditional creation under a deleted grandparent must not write");
			try (var _ = bosk.readSession()) {
				assertNull(grandparent.valueIfExists(), "The grandparent must remain absent");
				assertNull(parent.valueIfExists(), "The parent must remain absent");
				assertNull(child.valueIfExists(), "The child must remain absent");
			}
		}
	}

	abstract static class Base extends AbstractMongoDriverTest {
		@AfterEach
		void resetProbes() {
			MainDriver.resetProbes();
		}

		protected Bosk<TestEntity> newBosk(AtomicInteger writeCount, Bosk.DefaultStateFunction<TestEntity> initialState) throws InvalidTypeException {
			MainDriver.setProbes(TestProbes.noop().withWriteInterceptor(_ -> writeCount.incrementAndGet()));
			return new Bosk<>(
				boskName(),
				TestEntity.class,
				initialState,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		}
	}
}
