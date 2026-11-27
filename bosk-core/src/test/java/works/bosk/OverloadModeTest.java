package works.bosk;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
			BoskConfig.<TestRoot>builder().hookOverloadThreshold(1).build());
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
	void createdInQueueAndDeletedInBacklogIsReportedAsDeletion() throws InterruptedException, InvalidTypeException {
		Bosk<TestRoot> transitionBosk = new Bosk<>(
			"overload-queue-backlog-test",
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookOverloadThreshold(2).build());
		Refs transitionRefs = transitionBosk.rootReference().buildReferences(Refs.class);
		Identifier transientChildID = Identifier.from("transientChild");
		Reference<TestChild> transientChildRef = transitionRefs.child(transientChildID);
		Reference<String> stringRef = transitionRefs.childString(Identifier.from("child1"));

		BlockingHook hook = new BlockingHook();
		transitionBosk.hookRegistrar().registerHook("anyChildString", transitionRefs.anyChildString(), hook);
		hook.seen.clear();
		hook.blockNext();

		Thread updater = new Thread(() -> transitionBosk.driver().submitReplacement(stringRef, "v1"));
		updater.start();
		hook.awaitStarted();

		// These are queued while the hook is blocked.
		transitionBosk.driver().submitConditionalCreation(
			transientChildRef,
			new TestChild(transientChildID, "transient", TestEnum.OK, Catalog.empty()));
		// This one reaches the threshold, so the deletion is compacted instead.
		transitionBosk.driver().submitReplacement(stringRef, "v2");
		assertEquals(OVERLOAD, transitionBosk.hookMode(), "The queued work should have reached the threshold");
		transitionBosk.driver().submitDeletion(transientChildRef);

		hook.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
		assertEquals(Arrays.asList("v1", "v2", null), hook.seen,
			"The queue batch should skip the create, and the backlog batch should report the deletion");
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
	void compactedHookDoesNotInheritDiagnosticAttributes() throws InterruptedException {
		Identifier childID = Identifier.from("newChild");
		Reference<String> stringRef = refs.childString(childID);
		AtomicReference<MapValue<String>> observedAttributes = new AtomicReference<>();
		AtomicBoolean firstHook = new AtomicBoolean(true);
		CountDownLatch firstHookStarted = new CountDownLatch(1);
		CountDownLatch releaseFirstHook = new CountDownLatch(1);
		bosk.hookRegistrar().registerHook("string", stringRef, ref -> {
			observedAttributes.set(bosk.context().getAttributes());
			if (firstHook.compareAndSet(true, false)) {
				firstHookStarted.countDown();
				releaseFirstHook.await();
			}
		});

		Thread creator = new Thread(() -> {
			try (var _ = bosk.context().withAttribute("key", "value")) {
				bosk.driver().submitConditionalCreation(
					refs.child(childID),
					new TestChild(childID, "value", TestEnum.OK, Catalog.empty()));
			}
		});
		creator.start();

		assertTrue(firstHookStarted.await(10, SECONDS), "The first hook call should start");
		try (var _ = bosk.context().withAttribute("key", "value")) {
			bosk.driver().submitReplacement(stringRef, "v2");
			bosk.driver().submitReplacement(stringRef, "v3");
		}
		releaseFirstHook.countDown();

		creator.join(10_000);
		assertFalse(creator.isAlive(), "The creating thread should finish");
		assertEquals(MapValue.empty(), observedAttributes.get(), "Compacted hooks should not inherit update attributes");
	}

	@Test
	void registrationNotificationsCountTowardTheThreshold() throws InterruptedException, InvalidTypeException {
		Bosk<TestRoot> thresholdBosk = new Bosk<>(
			"overload-registration-threshold-test",
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookOverloadThreshold(2).build());
		Refs thresholdRefs = thresholdBosk.rootReference().buildReferences(Refs.class);
		Reference<String> stringRef = thresholdRefs.childString(Identifier.from("child1"));
		BlockingHook blocking = new BlockingHook();
		thresholdBosk.hookRegistrar().registerHook("blocking", stringRef, blocking);
		blocking.seen.clear();
		blocking.blockNext();

		Thread updater = new Thread(() -> thresholdBosk.driver().submitReplacement(stringRef, "v1"));
		updater.start();
		blocking.awaitStarted();

		// The drain is blocked, so registration notifications remain queued.
		thresholdBosk.hookRegistrar().registerHook("extra1", thresholdRefs.anyChildString(), ref -> {});
		assertEquals(STANDARD, thresholdBosk.hookMode(), "One queued registration is below the threshold");
		thresholdBosk.hookRegistrar().registerHook("extra2", thresholdRefs.anyChildString(), ref -> {});
		assertEquals(OVERLOAD, thresholdBosk.hookMode(), "The second queued registration should reach the threshold");

		blocking.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
	}

	@Test
	void oneUpdateMatchingSeveralHooksQueuesOneNotification() throws InterruptedException, InvalidTypeException {
		Bosk<TestRoot> multiHookBosk = new Bosk<>(
			"overload-multi-hook-test",
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookOverloadThreshold(2).build());
		Refs multiHookRefs = multiHookBosk.rootReference().buildReferences(Refs.class);
		Reference<String> stringRef = multiHookRefs.childString(Identifier.from("child1"));
		multiHookBosk.hookRegistrar().registerHook("specific", stringRef, ref -> {});
		multiHookBosk.hookRegistrar().registerHook("parameterized", multiHookRefs.anyChildString(), ref -> {});
		assertEquals(STANDARD, multiHookBosk.hookMode(), "The registration notifications should have drained");

		multiHookBosk.driver().submitReplacement(stringRef, "v1");

		assertEquals(STANDARD, multiHookBosk.hookMode(),
			"One update should queue one notification, however many hooks it matches");
	}

	@Test
	void thresholdOfTwoAllowsOneQueuedNotificationBeforeOverload() throws InterruptedException, InvalidTypeException {
		Bosk<TestRoot> thresholdBosk = new Bosk<>(
			"overload-queue-threshold-test",
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookOverloadThreshold(2).build());
		Refs thresholdRefs = thresholdBosk.rootReference().buildReferences(Refs.class);
		Reference<String> stringRef = thresholdRefs.childString(Identifier.from("child1"));
		BlockingHook hook = new BlockingHook();
		thresholdBosk.hookRegistrar().registerHook("string", stringRef, hook);
		hook.seen.clear();
		hook.blockNext();

		Thread updater = new Thread(() -> thresholdBosk.driver().submitReplacement(stringRef, "v1"));
		updater.start();
		hook.awaitStarted();
		assertEquals(STANDARD, thresholdBosk.hookMode(), "The first execution should run in standard mode");

		thresholdBosk.driver().submitReplacement(stringRef, "v2");
		assertEquals(STANDARD, thresholdBosk.hookMode(), "One queued notification should be allowed");

		thresholdBosk.driver().submitReplacement(stringRef, "v3");
		assertEquals(OVERLOAD, thresholdBosk.hookMode(), "The second queued notification should reach the threshold");

		hook.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
	}

	@ParameterizedTest
	@CsvSource({"0, OVERLOAD", "1, PROBATION"})
	void smallThresholdsCompactEveryUpdate(int threshold, OverloadController.Mode restingMode) throws InterruptedException, InvalidTypeException {
		Bosk<TestRoot> alwaysOverloadBosk = new Bosk<>(
			"overload-permanent-test-" + threshold,
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookOverloadThreshold(threshold).build());
		Refs alwaysOverloadRefs = alwaysOverloadBosk.rootReference().buildReferences(Refs.class);
		Reference<String> stringRef = alwaysOverloadRefs.childString(Identifier.from("child1"));

		BlockingHook hook = new BlockingHook();
		alwaysOverloadBosk.hookRegistrar().registerHook("string", stringRef, hook);
		hook.seen.clear();
		hook.blockNext();

		Thread updater = new Thread(() -> alwaysOverloadBosk.driver().submitReplacement(stringRef, "v1"));
		updater.start();
		hook.awaitStarted();
		assertEquals(OVERLOAD, alwaysOverloadBosk.hookMode(), "A threshold of " + threshold + " should not queue work for standard mode");

		alwaysOverloadBosk.driver().submitReplacement(stringRef, "v2");
		alwaysOverloadBosk.driver().submitReplacement(stringRef, "v3");

		hook.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
		assertEquals(List.of("v1", "v3"), hook.seen, "A threshold of " + threshold + " should compact queued updates");
		assertEquals(restingMode, alwaysOverloadBosk.hookMode(),
			"A threshold of " + threshold + " should settle in " + restingMode);
	}

	@Test
	void parameterizedScopeIsRebuiltFromNotificationPath() throws InterruptedException {
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
		Bosk<TestRoot> probationBosk = new Bosk<>(
			"overload-probation-test",
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookOverloadThreshold(3).build());
		Refs probationRefs = probationBosk.rootReference().buildReferences(Refs.class);
		Reference<String> stringRef = probationRefs.childString(Identifier.from("child1"));

		BlockingHook hook = new BlockingHook();
		probationBosk.hookRegistrar().registerHook("string", stringRef, hook);
		hook.seen.clear();

		hook.blockNext();
		Thread updater = new Thread(() -> probationBosk.driver().submitReplacement(stringRef, "v1"));
		updater.start();
		hook.awaitStarted();

		probationBosk.driver().submitReplacement(stringRef, "v2");
		probationBosk.driver().submitReplacement(stringRef, "v3");
		probationBosk.driver().submitReplacement(stringRef, "v4");
		probationBosk.driver().submitReplacement(stringRef, "v5");

		hook.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
		assertEquals(List.of("v1", "v5", "v5"), hook.seen,
			"The flushed queue and the backlog should each report the latest state");
		assertEquals(PROBATION, probationBosk.hookMode(), "A drained backlog should leave the driver on probation");

		probationBosk.driver().submitReplacement(stringRef, "v6");
		assertEquals(PROBATION, probationBosk.hookMode(), "A probation should process updates one at a time");
		probationBosk.driver().submitReplacement(stringRef, "v7");
		probationBosk.driver().submitReplacement(stringRef, "v8");

		assertEquals(STANDARD, probationBosk.hookMode(), "A completed probation should enter standard mode");
		assertEquals(List.of("v1", "v5", "v5", "v6", "v7", "v8"), hook.seen,
			"The probation should run each remaining update's hooks separately");
	}

	@Test
	void probationOverflowReturnsToOverload() throws InterruptedException, InvalidTypeException {
		Bosk<TestRoot> probationBosk = new Bosk<>(
			"overload-probation-failure-test",
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookOverloadThreshold(3).build());
		Refs probationRefs = probationBosk.rootReference().buildReferences(Refs.class);
		Reference<String> stringRef = probationRefs.childString(Identifier.from("child1"));

		BlockingHook hook = new BlockingHook();
		probationBosk.hookRegistrar().registerHook("string", stringRef, hook);
		hook.seen.clear();

		startOverloadAndDrainToProbation(probationBosk, stringRef, hook);
		assertEquals(PROBATION, probationBosk.hookMode(), "A drained backlog should leave the driver on probation");

		hook.blockNext();
		Thread updater = new Thread(() -> probationBosk.driver().submitReplacement(stringRef, "v8"));
		updater.start();
		hook.awaitStarted();
		assertEquals(PROBATION, probationBosk.hookMode(), "A probation should process updates one at a time");

		probationBosk.driver().submitReplacement(stringRef, "v9");
		probationBosk.driver().submitReplacement(stringRef, "v10");
		probationBosk.driver().submitReplacement(stringRef, "v11");
		probationBosk.driver().submitReplacement(stringRef, "v12");

		assertEquals(OVERLOAD, probationBosk.hookMode(), "Overflowing during a probation should return to overload mode");

		hook.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
		assertEquals(List.of("v1", "v5", "v5", "v8", "v12", "v12"), hook.seen,
			"The failed probation should compact the queued updates into the latest state");
		assertEquals(PROBATION, probationBosk.hookMode(), "A drained backlog should leave the driver on probation again");
	}

	@Test
	void probationSuccessCompactsFinalCatchUpBatch() throws InterruptedException, InvalidTypeException {
		Bosk<TestRoot> catchUpBosk = new Bosk<>(
			"overload-final-catch-up-test",
			TestRoot.class,
			AbstractBoskTest::initialState,
			BoskConfig.<TestRoot>builder().hookOverloadThreshold(5).build());
		Refs catchUpRefs = catchUpBosk.rootReference().buildReferences(Refs.class);
		Reference<String> stringRef = catchUpRefs.childString(Identifier.from("child1"));

		CatchUpHook hook = new CatchUpHook(catchUpBosk, stringRef);
		catchUpBosk.hookRegistrar().registerHook("string", stringRef, hook);
		hook.seen.clear();
		hook.nextValue.set(13);
		hook.probationSubmissions.set(5);
		hook.catchUpSubmissions.set(3);

		startOverloadWithBlockedBatch(catchUpBosk, stringRef, hook);
		assertEquals(PROBATION, catchUpBosk.hookMode(), "A drained backlog should leave the driver on probation");

		catchUpBosk.driver().submitReplacement(stringRef, "v12");

		assertEquals(STANDARD, catchUpBosk.hookMode(), "A completed catch-up should enter standard mode");
		assertEquals(List.of("v1", "v11", "v11", "v12", "v13", "v14", "v15", "v16", "v17", "v20"), hook.seen,
			"The probation should run each update's hooks separately, and the catch-up should compact its batch");
	}

	@Test
	void interruptDiscardsUnrunHookWork() throws InterruptedException {
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
		assertEquals(1, secondHookRan.getCount(), "An interrupt should discard the unrun hook work");

		bosk.driver().submitReplacement(stringRef, "afterInterrupt");

		assertTrue(secondHookRan.await(10, SECONDS), "A later update should run the hooks again");
	}

	private static void startOverloadAndDrainToProbation(Bosk<TestRoot> bosk, Reference<String> stringRef, BlockingHook hook) throws InterruptedException {
		hook.blockNext();
		Thread updater = new Thread(() -> bosk.driver().submitReplacement(stringRef, "v1"));
		updater.start();
		hook.awaitStarted();

		bosk.driver().submitReplacement(stringRef, "v2");
		bosk.driver().submitReplacement(stringRef, "v3");
		bosk.driver().submitReplacement(stringRef, "v4");
		bosk.driver().submitReplacement(stringRef, "v5");

		hook.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
		assertEquals(List.of("v1", "v5", "v5"), hook.seen,
			"The flushed queue and the backlog should each report the latest state");
	}

	/**
	 * Starts with an overload batch blocked so updates submitted now are compacted
	 * when the batch finishes, leaving the driver on probation.
	 */
	private static void startOverloadWithBlockedBatch(Bosk<TestRoot> bosk, Reference<String> stringRef, CatchUpHook hook) throws InterruptedException {
		hook.blockNext();
		Thread updater = new Thread(() -> bosk.driver().submitReplacement(stringRef, "v1"));
		updater.start();
		hook.awaitStarted();

		for (int i = 2; i <= 11; i++) {
			bosk.driver().submitReplacement(stringRef, "v" + i);
		}

		hook.unblock();
		updater.join(10_000);
		assertFalse(updater.isAlive(), "The updating thread should finish");
		assertEquals(List.of("v1", "v11", "v11"), hook.seen,
			"The flushed queue and the backlog are separate batches, each reporting the latest state");
	}

	private static final class CatchUpHook implements BoskHook<String> {
		final Bosk<TestRoot> bosk;
		final Reference<String> stringRef;
		final List<String> seen = new CopyOnWriteArrayList<>();
		final AtomicInteger probationSubmissions = new AtomicInteger();
		final AtomicInteger catchUpSubmissions = new AtomicInteger();
		final AtomicInteger nextValue = new AtomicInteger(12);
		final BlockingQueue<CountDownLatch> blocks = new LinkedBlockingQueue<>();
		final Semaphore blockStarted = new Semaphore(0);
		volatile CountDownLatch currentBlock;

		CatchUpHook(Bosk<TestRoot> bosk, Reference<String> stringRef) {
			this.bosk = bosk;
			this.stringRef = stringRef;
		}

		void blockNext() {
			blocks.add(new CountDownLatch(1));
		}

		void awaitStarted() throws InterruptedException {
			assertTrue(blockStarted.tryAcquire(10, SECONDS), "The blocking hook should start");
		}

		void unblock() {
			currentBlock.countDown();
		}

		@Override
		public void onChanged(Reference<String> reference) throws InterruptedException {
			seen.add(reference.valueIfExists());
			CountDownLatch release = blocks.poll();
			if (release != null) {
				currentBlock = release;
				blockStarted.release();
				release.await();
			}
			switch (bosk.hookMode()) {
				case PROBATION -> {
					if (probationSubmissions.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
						submitNext();
					}
				}
				case CATCH_UP -> {
					for (int remaining = catchUpSubmissions.getAndSet(0); remaining > 0; remaining--) {
						submitNext();
					}
				}
				default -> {
				}
			}
		}

		private void submitNext() {
			bosk.driver().submitReplacement(stringRef, "v" + nextValue.getAndIncrement());
		}
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
