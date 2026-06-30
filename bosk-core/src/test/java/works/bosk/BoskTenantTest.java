package works.bosk;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.BoskContext.Tenant;
import works.bosk.BoskContext.Tenant.TenantId;
import works.bosk.BoskDriver.EntireState.MultiTree;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NonexistentReferenceException;
import works.bosk.junit.Ante;
import works.bosk.junit.RunAnteTestsFirst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.BoskConfig.TenancyModel.PERSISTENT;
import static works.bosk.testing.BoskTestUtils.boskName;

@RunAnteTestsFirst
public class BoskTenantTest extends AbstractBoskTest {
	Bosk<TestRoot> bosk;
	TestRoot initialTenant1Root;
	TestRoot initialTenant2Root;

	BoskUpdateTest.Refs refs;

	public interface Refs {
		@ReferencePath("/entities/-entity-") Reference<TestEntity> entity(Identifier entity);
		@ReferencePath("/entities/-entity-/string") Reference<String> entityString(Identifier entity);
		@ReferencePath("/entities/-entity-/children/-child-") Reference<TestChild> child(Identifier entity, Identifier child);
		@ReferencePath("/entities/-entity-/id") Reference<Identifier> entityID(Identifier entity);
	}

	static final Identifier PARENT_ID = Identifier.from("parent");
	static final Identifier CHILD_1_ID = Identifier.from("child1");
	static final Identifier CHILD_4_ID = Identifier.from("child4"); // Initially nonexistent

	static final TenantId TENANT_1 = Tenant.setTo(Identifier.from("tenant1"));
	static final TenantId TENANT_2 = Tenant.setTo(Identifier.from("tenant2"));
	static final TenantId TENANT_3 = Tenant.setTo(Identifier.from("tenant3"));

	@BeforeEach
	void createBosk() throws InvalidTypeException {
		bosk = new Bosk<>(
			boskName(),
			TestRoot.class,
			this::foo,
			BoskConfig.<TestRoot>builder()
				.tenancyModel(PERSISTENT)
				.build());
		refs = bosk.buildReferences(BoskUpdateTest.Refs.class);
	}

	private MultiTree<TestRoot> foo(Bosk<TestRoot> bosk) {
		initialTenant1Root = initialRoot(bosk).withId(TENANT_1.tenant());
		initialTenant2Root = initialRoot(bosk).withId(TENANT_2.tenant());
		return MultiTree.singleton(
			TENANT_1, initialTenant1Root
		).with(
			TENANT_2, initialTenant2Root
		);
	}

	@Ante
	@Test
	void startingState() {
		try (var _ = bosk.readSession()) {
			assertEquals(foo(bosk), bosk.entireState());
			try (var _ = bosk.context().withTenant(TENANT_1)) {
				assertEquals(initialTenant1Root, bosk.rootReference().value());
			}
			try (var _ = bosk.context().withTenant(TENANT_2)) {
				assertEquals(initialTenant2Root, bosk.rootReference().value());
			}
			try (var _ = bosk.context().withTenant(TENANT_3)) {
				assertThrows(NonexistentReferenceException.class, () -> bosk.rootReference().value());
			}
		}
	}

	@Test
	void replace_works() throws IOException, InterruptedException {
		var stringRef = refs.entityString(PARENT_ID);
		String newString = "New string";
		try (var _ = bosk.context().withTenant(TENANT_1)) {
			bosk.driver().submitReplacement(stringRef, newString);
			bosk.driver().flush();
			try (var _ = bosk.readSession()) {
				assertEquals(newString, stringRef.value());
			}
		}
		assertTenant2Unaffected();
	}

	@Test
	void replaceRoot_createsTenant() throws IOException, InterruptedException {
		TestRoot newRoot = initialTenant1Root.withId(TENANT_3.tenant());
		try (var _ = bosk.context().withTenant(TENANT_3)) {
			bosk.driver().submitReplacement(bosk.rootReference(), newRoot);
			bosk.driver().flush();
			try (var _ = bosk.readSession()) {
				assertEquals(foo(bosk).with(TENANT_3, newRoot), bosk.entireState());
				assertEquals(newRoot, bosk.rootReference().value(),
					"Root update creates tenant");
			}
		}
		assertTenant2Unaffected();
	}

	@Test
	void conditionalCreateRoot_createsTenant() throws IOException, InterruptedException {
		TestRoot newRoot = initialTenant1Root.withId(TENANT_3.tenant());
		try (var _ = bosk.context().withTenant(TENANT_3)) {
			bosk.driver().submitConditionalCreation(bosk.rootReference(), newRoot);
			bosk.driver().flush();
			try (var _ = bosk.readSession()) {
				assertEquals(foo(bosk).with(TENANT_3, newRoot), bosk.entireState());
				assertEquals(newRoot, bosk.rootReference().value(),
					"Root update creates tenant");
			}
		}
		assertTenant2Unaffected();
	}

	@Test
	void deleteRoot_deletesTenant() throws IOException, InterruptedException {
		try (var _ = bosk.context().withTenant(TENANT_1)) {
			bosk.driver().submitDeletion(bosk.rootReference());
			bosk.driver().flush();
			try (var _ = bosk.readSession()) {
				assertEquals(MultiTree.singleton(TENANT_2, initialTenant2Root), bosk.entireState());
				assertThrows(NonexistentReferenceException.class, () -> bosk.rootReference().value(),
					"Root deletion deletes tenant");
			}
		}
		assertTenant2Unaffected();
	}

	private void assertTenant2Unaffected() {
		try (var _ = bosk.context().withTenant(TENANT_2); var _ = bosk.readSession()) {
			assertEquals(initialTenant2Root, bosk.rootReference().value(),
				"Tenant 2 is unaffected");
		}
	}
}
