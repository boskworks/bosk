package works.bosk.testing.drivers;

import java.io.IOException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskDriver;
import works.bosk.CatalogReference;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Identifier;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.drivers.ReplicaSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.util.Classes;

import static java.lang.Thread.currentThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;

public abstract class AbstractDriverTest {
	protected final Identifier child1ID = Identifier.from("child1");
	protected final Identifier child2ID = Identifier.from("child2");
	protected Bosk<TestEntity> canonicalBosk;
	protected Bosk<TestEntity> bosk;
	protected BoskDriver driver;
	private volatile String oldThreadName;

	@BeforeEach
	void logStart(TestInfo testInfo) {
		oldThreadName = Thread.currentThread().getName();
		String newThreadName = "test: " + testInfo.getDisplayName();
		Thread.currentThread().setName(newThreadName);
		logTest("/=== Start", testInfo);
		LOGGER.debug("Old thread name was {}", oldThreadName);
	}

	@AfterEach
	void logDone(TestInfo testInfo) {
		logTest("\\=== Done", testInfo);
		Thread.currentThread().setName(oldThreadName);
	}

	private static void logTest(String verb, TestInfo testInfo) {
		String method =
			testInfo.getTestClass().map(Class::getSimpleName).orElse(null)
				+ "."
				+ testInfo.getTestMethod().map(Method::getName).orElse(null);
		LOGGER.info("{} {} {}", verb, method, testInfo.getDisplayName());
	}

	protected void setupBosksAndReferences(DriverFactory<TestEntity> driverFactory) {
		// This is the bosk whose behaviour we'll consider to be correct by definition
		canonicalBosk = new Bosk<>(boskName("Canonical", 1), TestEntity.class, AbstractDriverTest::initialRoot, BoskConfig.simple());

		// This is the bosk we're testing
		bosk = new Bosk<>(
			boskName("Test", 1),
			TestEntity.class,
			AbstractDriverTest::initialRoot,
			BoskConfig.<TestEntity>builder().driverFactory(DriverStack.of(
				ReplicaSet.mirroringTo(canonicalBosk),
				DriverStateVerifier.wrap(driverFactory, TestEntity.class, AbstractDriverTest::initialRoot)
			)).build());
		driver = bosk.driver();
	}

	public static TestEntity initialRoot(Bosk<TestEntity> b) throws InvalidTypeException {
		return TestEntity.empty(Identifier.from("root"), b.rootReference().thenCatalog(TestEntity.class, Path.just(TestEntity.Fields.catalog)));
	}

	protected TestEntity autoInitialize(Reference<TestEntity> ref) {
		if (ref.path().isEmpty()) {
			// Root always exists; nothing to do
			return null;
		} else {
			autoInitialize(ref.enclosingReference(TestEntity.class));
			TestEntity newEntity = emptyEntityAt(ref);
			if (isInNestedSideTable(ref)) {
				Reference<SideTable<TestEntity, TestEntity>> outerRef;
				try {
					outerRef = ref.root().then(Classes.sideTable(TestEntity.class, TestEntity.class), ref.path().truncatedBy(1));
				} catch (InvalidTypeException e) {
					throw new AssertionError(e);
				}
				driver.submitConditionalCreation(outerRef, SideTable.of(newEntity.listing().domain(), Identifier.from(ref.path().lastSegment()), newEntity));
			} else {
				driver.submitConditionalCreation(ref, newEntity);
			}
			return newEntity;
		}
	}

	private boolean isInNestedSideTable(Reference<TestEntity> ref) {
		Path path = ref.path();
		if (path.length() < 3) {
			return false;
		} else {
			return path.truncatedBy(2).lastSegment().equals(TestEntity.Fields.nestedSideTable);
		}
	}

	TestEntity emptyEntityAt(Reference<TestEntity> ref) {
		CatalogReference<TestEntity> catalogRef;
		try {
			catalogRef = ref.thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
		} catch (InvalidTypeException e) {
			throw new AssertionError("Every entity should have a catalog in it", e);
		}
		return TestEntity.empty(Identifier.from(ref.path().lastSegment()), catalogRef);
	}

	protected TestEntity newEntity(Identifier id, CatalogReference<TestEntity> enclosingCatalogRef) throws InvalidTypeException {
		return TestEntity.empty(id, enclosingCatalogRef.then(id).thenCatalog(TestEntity.class, TestEntity.Fields.catalog));
	}

	protected void assertCorrectBoskContents() {
		LOGGER.debug("assertCorrectBoskContents");
		try {
			driver.flush();
		} catch (InterruptedException e) {
			currentThread().interrupt();
			throw new AssertionError("Unexpected interruption", e);
		} catch (IOException e) {
			throw new AssertionError("Unexpected exception", e);
		}
		TestEntity expected, actual;
		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadSession session = canonicalBosk.readSession()) {
			expected = canonicalBosk.rootReference().value();
		}
		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadSession session = bosk.readSession()) {
			actual = bosk.rootReference().value();
		}
		assertEquals(expected, actual);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractDriverTest.class);
}
