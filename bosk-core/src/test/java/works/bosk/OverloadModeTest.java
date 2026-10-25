package works.bosk;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.libtesting.AbstractBoskTest;

import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.OverloadController.Mode.OVERLOAD;
import static works.bosk.OverloadController.Mode.PROBATION;
import static works.bosk.OverloadController.Mode.STANDARD;

public class OverloadModeTest extends AbstractBoskTest {
	public interface Refs {
		@ReferencePath("/entities/parent/children/-child-")
		Reference<TestChild> child(Identifier child);

		@ReferencePath("/entities/parent/children/-child-/string")
		Reference<String> childString(Identifier child);

		@ReferencePath("/entities/parent/children/-child-/string")
		Reference<String> anyChildString();
	}

	Bosk<TestRoot> bosk;
	Refs refs;

	@BeforeEach
	void setUpBosk() throws InvalidTypeException {
		bosk = new Bosk<>(
			"overload-test",
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookQueueMaxSize(1).build());
		refs = bosk.rootReference().buildReferences(Refs.class);
	}

	@Test
	void pendingHookRunsOnceAgainstLatestState() throws InterruptedException {
		Identifier childID = Identifier.from("newChild");
		Reference<TestChild> childRef = refs.child(childID);
		Reference<String> stringRef = refs.childString(childID);

		List<String> observed = new CopyOnWriteArrayList<>();
		CountDownLatch firstHookStarted = new CountDownLatch(1);
		CountDownLatch releaseFirstHook = new CountDownLatch(1);
		bosk.hookRegistrar().registerHook("string", stringRef, ref -> {
			observed.add(ref.valueIfExists());
			if (observed.size() == 1) {
				firstHookStarted.countDown();
				releaseFirstHook.await();
			}
		});

		Thread creator = new Thread(() -> bosk.driver().submitConditionalCreation(
			childRef,
			new TestChild(childID, "v1", TestEnum.OK, Catalog.empty())));
		creator.start();

		assertTrue(firstHookStarted.await(10, SECONDS), "The first hook call should start");
		bosk.driver().submitReplacement(stringRef, "v2");
		bosk.driver().submitReplacement(stringRef, "v3");
		releaseFirstHook.countDown();

		creator.join(10_000);
		assertFalse(creator.isAlive(), "The creating thread should finish");
		assertEquals(List.of("v1", "v3"), observed, "The pending hook should see the latest state once");
	}

	@Test
	void createdAndDeletedReferenceIsNotReported() throws InterruptedException {
		Identifier hookAChildID = Identifier.from("hookAChild");
		Identifier transientChildID = Identifier.from("transientChild");
		Reference<TestChild> hookAChildRef = refs.child(hookAChildID);
		Reference<TestChild> transientChildRef = refs.child(transientChildID);

		List<String> hookASeen = new CopyOnWriteArrayList<>();
		List<String> transientSeen = new CopyOnWriteArrayList<>();
		CountDownLatch hookAStarted = new CountDownLatch(1);
		CountDownLatch releaseHookA = new CountDownLatch(1);
		bosk.hookRegistrar().registerHook("A", refs.childString(hookAChildID), ref -> {
			hookASeen.add(ref.valueIfExists());
			if (hookASeen.size() == 1) {
				hookAStarted.countDown();
				releaseHookA.await();
			}
		});
		bosk.hookRegistrar().registerHook("transient", refs.childString(transientChildID), ref ->
			transientSeen.add(ref.valueIfExists()));

		Thread creator = new Thread(() -> bosk.driver().submitConditionalCreation(
			hookAChildRef,
			new TestChild(hookAChildID, "a", TestEnum.OK, Catalog.empty())));
		creator.start();

		assertTrue(hookAStarted.await(10, SECONDS), "Hook A should start");
		bosk.driver().submitConditionalCreation(
			transientChildRef,
			new TestChild(transientChildID, "transient", TestEnum.OK, Catalog.empty()));
		bosk.driver().submitDeletion(transientChildRef);
		releaseHookA.countDown();

		creator.join(10_000);
		assertFalse(creator.isAlive(), "The creating thread should finish");
		assertEquals(List.of("a"), hookASeen, "Hook A should run for its surviving child");
		assertEquals(List.of(), transientSeen, "A created and deleted child needs no hook call");
	}

	@Test
	void preExistingDeletionIsReported() {
		List<String> seen = new CopyOnWriteArrayList<>();
		bosk.hookRegistrar().registerHook("string", refs.childString(Identifier.from("child1")), ref ->
			seen.add(ref.valueIfExists()));
		seen.clear();

		bosk.driver().submitDeletion(refs.child(Identifier.from("child1")));

		assertEquals(singletonList(null), seen, "A pre-existing deleted reference should be reported");
	}

	@Test
	void coalescedHookDoesNotInheritDiagnosticAttributes() throws InterruptedException {
		Identifier childID = Identifier.from("newChild");
		AtomicReference<MapValue<String>> observedAttributes = new AtomicReference<>();
		CountDownLatch hookRan = new CountDownLatch(1);
		bosk.hookRegistrar().registerHook("string", refs.childString(childID), ref -> {
			observedAttributes.set(bosk.context().getAttributes());
			hookRan.countDown();
		});

		try (var _ = bosk.context().withAttribute("key", "value")) {
			bosk.driver().submitConditionalCreation(
				refs.child(childID),
				new TestChild(childID, "value", TestEnum.OK, Catalog.empty()));
		}

		assertTrue(hookRan.await(10, SECONDS), "The hook should run");
		assertEquals(MapValue.empty(), observedAttributes.get(), "Coalesced hooks should not inherit update attributes");
	}

	@Test
	void registrationCatchUpOverflowsQueueLimit() {
		List<String> seen = new CopyOnWriteArrayList<>();
		bosk.hookRegistrar().registerHook("anyChildString", refs.anyChildString(), ref ->
			seen.add(ref.valueIfExists()));

		assertEquals(List.of("child1", "child2", "child3"), seen,
			"Registration catch-up should call the hook for every matching child");
	}

	@Test
	void parameterizedScopeIsRebuiltFromPendingPath() throws InterruptedException {
		Identifier childID = Identifier.from("newChild");
		List<String> seen = new CopyOnWriteArrayList<>();
		bosk.hookRegistrar().registerHook("anyChildString", refs.anyChildString(), ref ->
			seen.add(ref.valueIfExists()));
		seen.clear();

		bosk.driver().submitConditionalCreation(
			refs.child(childID),
			new TestChild(childID, "value", TestEnum.OK, Catalog.empty()));

		assertEquals(List.of("value"), seen, "The recorded path should be bound back to the hook's parameterized scope");
	}

	@Test
	void quietBacklogStartsProbationThenCommitsToStandard() throws InterruptedException, InvalidTypeException {
		Bosk<TestRoot> trialBosk = new Bosk<>(
			"overload-probation-test",
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookQueueMaxSize(3).build());
		Refs trialRefs = trialBosk.rootReference().buildReferences(Refs.class);
		Reference<String> stringRef = trialRefs.childString(Identifier.from("child1"));

		BlockingHook hook = new BlockingHook();
		trialBosk.hookRegistrar().registerHook("string", stringRef, hook);
		hook.seen.clear();

		hook.blockNext();
		Thread updater = new Thread(() -> trialBosk.driver().submitReplacement(stringRef, "v1"));
		updater.start();
		hook.awaitStarted();

		trialBosk.driver().submitReplacement(stringRef, "v2");
		trialBosk.driver().submitReplacement(stringRef, "v3");
		trialBosk.driver().submitReplacement(stringRef, "v4");

		hook.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
		assertEquals(List.of("v1", "v4"), hook.seen, "The overload batch should report only the latest state");
		assertEquals(OVERLOAD, trialBosk.hookMode(), "The empty queue should leave the driver in overload mode");

		trialBosk.driver().submitReplacement(stringRef, "v5");
		assertEquals(PROBATION, trialBosk.hookMode(), "The first queued update should start a probation");
		trialBosk.driver().submitReplacement(stringRef, "v6");
		trialBosk.driver().submitReplacement(stringRef, "v7");

		assertEquals(STANDARD, trialBosk.hookMode(), "A completed probation should enter standard mode");
		assertEquals(List.of("v1", "v4", "v5", "v6", "v7"), hook.seen,
			"The probation should deliver each remaining update separately");
	}

	@Test
	void probationOverflowReturnsToOverload() throws InterruptedException, InvalidTypeException {
		Bosk<TestRoot> trialBosk = new Bosk<>(
			"overload-probation-failure-test",
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookQueueMaxSize(3).build());
		Refs trialRefs = trialBosk.rootReference().buildReferences(Refs.class);
		Reference<String> stringRef = trialRefs.childString(Identifier.from("child1"));

		BlockingHook hook = new BlockingHook();
		trialBosk.hookRegistrar().registerHook("string", stringRef, hook);
		hook.seen.clear();

		startOverloadAndDrainToOverload(trialBosk, stringRef, hook);
		assertEquals(OVERLOAD, trialBosk.hookMode(), "The empty queue should leave the driver in overload mode");

		hook.blockNext();
		Thread updater = new Thread(() -> trialBosk.driver().submitReplacement(stringRef, "v8"));
		updater.start();
		hook.awaitStarted();
		assertEquals(PROBATION, trialBosk.hookMode(), "The first queued update should start a trial");

		trialBosk.driver().submitReplacement(stringRef, "v9");
		trialBosk.driver().submitReplacement(stringRef, "v10");
		trialBosk.driver().submitReplacement(stringRef, "v11");

		assertEquals(OVERLOAD, trialBosk.hookMode(), "Overflowing during a trial should return to overload mode");

		hook.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
		assertEquals(List.of("v1", "v4", "v8", "v11"), hook.seen,
			"The failed trial should coalesce the queued updates into the latest state");
		assertEquals(OVERLOAD, trialBosk.hookMode(), "A failed trial should stay in overload mode");
	}

	@Test
	void interruptedOverloadBatchRequeuesUnrunHooks() throws InterruptedException {
		Identifier childID = Identifier.from("interruptedChild");
		Reference<TestChild> childRef = refs.child(childID);
		Reference<String> stringRef = refs.childString(childID);

		CountDownLatch firstHookStarted = new CountDownLatch(1);
		CountDownLatch secondHookRan = new CountDownLatch(1);
		AtomicBoolean firstHookShouldBlock = new AtomicBoolean(true);
		bosk.hookRegistrar().registerHook("first", stringRef, ref -> {
			if (firstHookShouldBlock.compareAndSet(true, false)) {
				firstHookStarted.countDown();
				new CountDownLatch(1).await();
			}
		});
		bosk.hookRegistrar().registerHook("second", stringRef, ref -> secondHookRan.countDown());

		Thread submitter = new Thread(() -> bosk.driver().submitConditionalCreation(
			childRef,
			new TestChild(childID, "initial", TestEnum.OK, Catalog.empty())));
		submitter.start();

		assertTrue(firstHookStarted.await(10, SECONDS), "The first hook should start");
		submitter.interrupt();
		submitter.join(10_000);
		assertFalse(submitter.isAlive(), "The interrupted submitter should finish");

		bosk.driver().submitReplacement(stringRef, "afterInterrupt");

		assertTrue(secondHookRan.await(10, SECONDS), "The unrun hook should be requeued and run later");
	}

	private static void startOverloadAndDrainToOverload(Bosk<TestRoot> bosk, Reference<String> stringRef, BlockingHook hook) throws InterruptedException {
		hook.blockNext();
		Thread updater = new Thread(() -> bosk.driver().submitReplacement(stringRef, "v1"));
		updater.start();
		hook.awaitStarted();

		bosk.driver().submitReplacement(stringRef, "v2");
		bosk.driver().submitReplacement(stringRef, "v3");
		bosk.driver().submitReplacement(stringRef, "v4");

		hook.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
		assertEquals(List.of("v1", "v4"), hook.seen, "The overload batch should report only the latest state");
	}

	private static final class BlockingHook implements BoskHook<String> {
		final List<String> seen = new CopyOnWriteArrayList<>();
		final AtomicBoolean blocking = new AtomicBoolean(false);
		volatile CountDownLatch started;
		volatile CountDownLatch release;

		void blockNext() {
			started = new CountDownLatch(1);
			release = new CountDownLatch(1);
			blocking.set(true);
		}

		void awaitStarted() throws InterruptedException {
			assertTrue(started.await(10, SECONDS), "The blocking hook should start");
		}

		void unblock() {
			release.countDown();
		}

		@Override
		public void onChanged(Reference<String> reference) throws InterruptedException {
			seen.add(reference.valueIfExists());
			if (blocking.compareAndSet(true, false)) {
				started.countDown();
				release.await();
			}
		}
	}
}
