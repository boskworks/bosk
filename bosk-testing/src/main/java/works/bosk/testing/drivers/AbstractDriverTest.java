package works.bosk.testing.drivers;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskConfig.TenancyModel;
import works.bosk.BoskConfig.TenancyModel.Explicit;
import works.bosk.BoskConfig.TenancyModel.Fixed;
import works.bosk.BoskConfig.TenancyModel.None;
import works.bosk.BoskContext.ContextScope;
import works.bosk.BoskContext.Tenant;
import works.bosk.BoskDriver;
import works.bosk.BoskDriver.InitialState;
import works.bosk.CatalogReference;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Identifier;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.drivers.ReplicaSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.Injected;
import works.bosk.junit.Injector;
import works.bosk.testing.drivers.AbstractDriverTest.ScenarioInjector;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.testing.drivers.state.TestEntity.Fields;
import works.bosk.util.Classes;

import static java.lang.Thread.currentThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;

@InjectFields
@InjectFrom(ScenarioInjector.class)
public abstract class AbstractDriverTest {
	public static final Identifier TENANT1 = Identifier.from("tenant1");
	protected final Identifier child1ID = Identifier.from("child1");
	protected final Identifier child2ID = Identifier.from("child2");
	protected TestInfo testInfo;
	@Injected protected Scenario scenario;
	protected Bosk<TestEntity> canonicalBosk;
	protected Bosk<TestEntity> bosk;
	protected ContextScope tenantScope;
	protected BoskDriver driver;
	private volatile String oldThreadName;

	public enum Scenario {
		NO_TENANTS(TenancyModel.NONE, Tenant.NONE),
		FIXED_TENANT(new Fixed(TENANT1), Tenant.setTo(TENANT1)),
		TRANSIENT_TENANT(TenancyModel.TRANSIENT, Tenant.setTo(TENANT1))
		;

		public final TenancyModel tenancyModel;

		/**
		 * The tenant to establish at the start of tests.
		 * This is intended as a convenience to allow the bulk of tests
		 * to run without worrying about establishing a tenant,
		 * not to describe the tenant state automatically established by the tenancy model.
		 *
		 * @see #automaticallyEstablishedTenant()
		 */
		public final Tenant startingTenant;

		/**
		 * @return the tenant state automatically established by the tenancy model
		 */
		public Tenant automaticallyEstablishedTenant() {
			return switch (tenancyModel) {
				case None _ -> Tenant.NONE;
				case Explicit _ -> Tenant.NOT_ESTABLISHED;
				case Fixed(var id) -> Tenant.setTo(id);
			};
		}

		Scenario(TenancyModel tenancyModel, Tenant startingTenant) {
			this.tenancyModel = tenancyModel;
			this.startingTenant = startingTenant;
		}

	}

	record ScenarioInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType.equals(Scenario.class);
		}

		@Override
		public List<?> values() {
			return Arrays.asList(Scenario.values());
		}
	}

	@BeforeEach
	void clearTenantScope() {
		tenantScope = null;
	}

	@AfterEach
	void closeTenantScope() {
		if (tenantScope != null) {
			tenantScope.close();
			tenantScope = null;
		}
	}

	@BeforeEach
	void logStart(TestInfo testInfo) {
		this.testInfo = testInfo;
		oldThreadName = currentThread().getName();
		String newThreadName = "test: " + testInfo.getDisplayName();
		currentThread().setName(newThreadName);
		logTest("/=== Start");
		LOGGER.debug("Old thread name was {}", oldThreadName);
	}

	@AfterEach
	void logDone() {
		logTest("\\=== Done");
		currentThread().setName(oldThreadName);
	}

	private void logTest(String verb) {
		String method =
			testInfo.getTestClass().map(Class::getSimpleName).orElse(null)
				+ "."
				+ testInfo.getTestMethod().map(Method::getName).orElse(null);
		LOGGER.info("{} {} {}", verb, method, testInfo.getDisplayName());
	}

	protected void setupBosksAndReferences(DriverFactory<TestEntity> driverFactory) {
		TenancyModel tenancyModel = scenario.tenancyModel;

		// This is the bosk whose behaviour we'll consider to be correct by definition
		canonicalBosk = new Bosk<>(
			boskName("Canonical", 1),
			TestEntity.class,
			this::initialState,
			BoskConfig.<TestEntity>builder()
				.tenancyModel(tenancyModel)
				.build()
		);

		// This is the bosk we're testing
		bosk = new Bosk<>(
			boskName("Test", 1),
			TestEntity.class,
			this::initialState,
			BoskConfig.<TestEntity>builder()
				.driverFactory(DriverStack.of(
					ReplicaSet.mirroringTo(canonicalBosk),
					DriverStateVerifier.wrap(driverFactory, TestEntity.class, this::initialState)
				))
				.tenancyModel(tenancyModel)
				.build());
		driver = bosk.driver();
		tenantScope = bosk.context().withMaybeTenant(scenario.startingTenant);
	}

	public InitialState<TestEntity> initialState(Bosk<TestEntity> b) throws InvalidTypeException {
		TestEntity root = TestEntity.empty(Identifier.from("root"), b.rootReference().thenCatalog(TestEntity.class, Path.just(Fields.catalog)));
		return InitialState.of(root);
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
			return path.truncatedBy(2).lastSegment().equals(Fields.nestedSideTable);
		}
	}

	TestEntity emptyEntityAt(Reference<TestEntity> ref) {
		CatalogReference<TestEntity> catalogRef;
		try {
			catalogRef = ref.thenCatalog(TestEntity.class, Fields.catalog);
		} catch (InvalidTypeException e) {
			throw new AssertionError("Every entity should have a catalog in it", e);
		}
		return TestEntity.empty(Identifier.from(ref.path().lastSegment()), catalogRef);
	}

	protected TestEntity newEntity(Identifier id, CatalogReference<TestEntity> enclosingCatalogRef) throws InvalidTypeException {
		return TestEntity.empty(id, enclosingCatalogRef.then(id).thenCatalog(TestEntity.class, Fields.catalog));
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
