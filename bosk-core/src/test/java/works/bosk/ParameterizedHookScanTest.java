package works.bosk;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.libtesting.AbstractBoskTest;
import works.bosk.libtesting.TestEntityBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A parameterized hook should not scan a container that is unchanged by an update.
 *
 * <p>
 * Replacing a parent object while keeping the same children catalog changes no child,
 * so the hook does not run. Before the guard in {@code HookRunner.triggerCascade},
 * every matching hook still enumerated the catalog and looked up every entry,
 * making the update O(hooks × children) in time and allocation.
 */
public class ParameterizedHookScanTest extends AbstractBoskTest {
	public interface Refs {
		@ReferencePath("/entities/parent")
		Reference<TestEntity> parent();

		@ReferencePath("/entities/parent/children/-child-/string")
		Reference<String> anyChildString();
	}

	@Test
	void parentUpdate_doesNotScanUnchangedChildCatalog() throws InvalidTypeException {
		int childCount = 10_000;
		int hookCount = 1;
		Bosk<TestRoot> bosk = new Bosk<>(
			"unchanged-container-hook-scan",
			TestRoot.class,
			b -> initialRoot(b, childCount),
			BoskConfig.<TestRoot>builder().build());
		Refs refs = bosk.rootReference().buildReferences(Refs.class);

		AtomicInteger hookCalls = new AtomicInteger();
		for (int i = 0; i < hookCount; i++) {
			bosk.hookRegistrar().registerHook("hook" + i, refs.anyChildString(), _ -> hookCalls.incrementAndGet());
		}
		assertEquals(childCount * hookCount, hookCalls.get(), "Hook runs for every child on registration");
		hookCalls.set(0);

		TestEntity parent = readParent(bosk, refs);
		bosk.driver().submitReplacement(refs.parent(), parent.withString("warmup"));
		parent = readParent(bosk, refs);

		assumeTrue(threadAllocatedBytes() >= 0, "Thread allocation measurement is unavailable");
		long before = threadAllocatedBytes();
		bosk.driver().submitReplacement(refs.parent(), parent.withString("measured"));
		long allocated = threadAllocatedBytes() - before;

		assertEquals(0, hookCalls.get(), "An unchanged child catalog must not trigger the child hook");
		assertTrue(allocated < 100_000,
			"A parent update must not enumerate the unchanged child catalog; allocated " + allocated + " bytes");
	}

	@Test
	void parentUpdate_withOneChangedChild_triggersChildHook() throws InvalidTypeException {
		int childCount = 10_000;
		int hookCount = 1;
		Bosk<TestRoot> bosk = new Bosk<>(
			"changed-container-hook-scan",
			TestRoot.class,
			b -> initialRoot(b, childCount),
			BoskConfig.<TestRoot>builder().build());
		Refs refs = bosk.rootReference().buildReferences(Refs.class);

		AtomicInteger hookCalls = new AtomicInteger();
		for (int i = 0; i < hookCount; i++) {
			bosk.hookRegistrar().registerHook("hook" + i, refs.anyChildString(), _ -> hookCalls.incrementAndGet());
		}
		assertEquals(childCount * hookCount, hookCalls.get(), "Hook runs for every child on registration");
		hookCalls.set(0);

		TestEntity parent = readParent(bosk, refs);
		Identifier warmupID = parent.children().ids().iterator().next();
		bosk.driver().submitReplacement(refs.parent(), parentWithChangedChild(parent, warmupID, "warmup"));
		parent = readParent(bosk, refs);
		hookCalls.set(0);

		Identifier measuredID = parent.children().ids().iterator().next();
		bosk.driver().submitReplacement(refs.parent(), parentWithChangedChild(parent, measuredID, "measured"));

		assertEquals(1, hookCalls.get(), "The changed child should trigger the child hook once");
	}

	@Test
	void ids_doesNotCopyTheIdentifiers() throws InvalidTypeException {
		int childCount = 10_000;
		Bosk<TestRoot> bosk = new Bosk<>(
			"allocation-free-ids",
			TestRoot.class,
			b -> initialRoot(b, childCount),
			BoskConfig.<TestRoot>builder().build());
		Refs refs = bosk.rootReference().buildReferences(Refs.class);

		Catalog<TestChild> children;
		try (var _ = bosk.readSession()) {
			children = refs.parent().value().children();
		}

		assumeTrue(threadAllocatedBytes() >= 0, "Thread allocation measurement is unavailable");
		long before = threadAllocatedBytes();
		Collection<Identifier> ids = children.ids();
		long allocated = threadAllocatedBytes() - before;

		assertEquals(childCount, ids.size());
		assertTrue(allocated < 10_000,
			"ids() must not copy the identifiers; allocated " + allocated + " bytes");
		assertEquals(Identifier.from("child0"), List.copyOf(ids).getFirst());
		assertEquals(Identifier.from("child9999"), List.copyOf(ids).getLast());
	}

	private static TestEntity parentWithChangedChild(TestEntity parent, Identifier childID, String value) {
		Catalog<TestChild> children = parent.children().with(parent.children().get(childID).withString(value));
		return parent.withChildren(children);
	}

	private static TestEntity readParent(Bosk<TestRoot> bosk, Refs refs) {
		try (var _ = bosk.readSession()) {
			return refs.parent().value();
		}
	}

	private static TestRoot initialRoot(Bosk<TestRoot> bosk, int childCount) throws InvalidTypeException {
		TestRoot base = AbstractBoskTest.initialState(bosk);
		TestEntityBuilder builder = new TestEntityBuilder(bosk);
		Identifier parentID = Identifier.from("parent");
		Catalog<TestChild> children = Catalog.of(IntStream.range(0, childCount)
			.mapToObj(i -> new TestChild(Identifier.from("child" + i), "v" + i, TestEnum.OK, Catalog.empty())));
		TestEntity parent = builder.blankEntity(parentID, TestEnum.OK).withChildren(children);
		return base.withEntities(Catalog.of(parent));
	}

	private static long threadAllocatedBytes() {
		return ((ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
	}
}
