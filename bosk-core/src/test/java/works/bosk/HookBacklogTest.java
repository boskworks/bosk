package works.bosk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookBacklogTest {
	final HookBacklog backlog = new HookBacklog();
	final Path pathA = Path.just("a");
	final Path pathB = Path.of("b", "c");

	@Test
	void creationThenDeletion_cancels() {
		backlog.add(0, pathA, false, true);
		backlog.add(0, pathA, true, false);
		assertTrue(backlog.isEmpty(), "A created and deleted reference needs no hook call");
	}

	@Test
	void deletionOfPreExistingReference_remainsPending() {
		backlog.add(0, pathA, true, false);
		assertFalse(backlog.isEmpty(), "A deleted pre-existing reference needs a deletion call");
		HookBacklog.PendingHooks hooks = backlog.detach().get(pathA);
		assertTrue(hooks.toRun().contains(0), "Hook must be pending");
		assertTrue(hooks.existedAtBaseline(0), "The reference must have existed before the first call");
	}

	@Test
	void firstBaselineIsKeptAcrossLaterChanges() {
		backlog.add(0, pathA, false, true);
		backlog.add(0, pathA, true, true);
		HookBacklog.PendingHooks hooks = backlog.detach().get(pathA);
		assertFalse(hooks.existedAtBaseline(0), "The baseline must come from the first pending change");
	}

	@Test
	void nonzeroHookIndexIsStored() {
		backlog.add(5, pathA, true, true);
		HookBacklog.PendingHooks hooks = backlog.detach().get(pathA);
		assertFalse(hooks.toRun().contains(0), "Hook index 0 must not be pending");
		assertTrue(hooks.toRun().contains(5), "Hook index 5 must be pending");
		assertTrue(hooks.existedAtBaseline(5), "The baseline for hook index 5 must be present");
	}

	@Test
	void hooksOnSamePathKeepIndependentBaselines() {
		backlog.add(0, pathA, false, true);
		backlog.add(1, pathA, true, true);
		backlog.add(0, pathA, true, false);
		HookBacklog.PendingHooks hooks = backlog.detach().get(pathA);
		assertFalse(hooks.toRun().contains(0), "Hook 0 must have nothing pending after a created-then-deleted reference");
		assertTrue(hooks.toRun().contains(1), "Hook 1 must have a pending deletion call");
		assertTrue(hooks.existedAtBaseline(1), "Hook 1's baseline must be independent of hook 0's");
	}

	@Test
	void recreatedReference_startsWithFreshBaseline() {
		backlog.add(0, pathA, false, true);
		backlog.add(0, pathA, true, false);
		assertTrue(backlog.isEmpty(), "Transient reference must be discarded");
		backlog.add(0, pathA, false, true);
		HookBacklog.PendingHooks hooks = backlog.detach().get(pathA);
		assertTrue(hooks.toRun().contains(0), "Recreated reference must be pending again");
		assertFalse(hooks.existedAtBaseline(0), "A recreation must start a fresh baseline");
	}

	@Test
	void detach_emptiesTheBacklog() {
		backlog.add(0, pathA, true, true);
		Map<Path, HookBacklog.PendingHooks> batch = backlog.detach();
		assertEquals(List.of(pathA), new ArrayList<>(batch.keySet()), "Batch must contain the pending path");
		assertTrue(backlog.isEmpty(), "Detached work must leave the backlog empty");
	}

	@Test
	void detach_preservesFirstSeenOrder() {
		backlog.add(0, pathB, true, true);
		backlog.add(0, pathA, true, true);
		Map<Path, HookBacklog.PendingHooks> batch = backlog.detach();
		assertEquals(List.of(pathB, pathA), new ArrayList<>(batch.keySet()), "Paths must be in first-seen order");
	}

	@Test
	void mergeEarlierRestoresUnrunCallsWithEarlierBaseline() {
		backlog.add(0, pathA, false, true);
		Map<Path, HookBacklog.PendingHooks> batch = backlog.detach();
		backlog.add(0, pathA, true, true);
		backlog.mergeEarlier(batch);
		HookBacklog.PendingHooks hooks = backlog.detach().get(pathA);
		assertTrue(hooks.toRun().contains(0), "Unrun call must be restored");
		assertFalse(hooks.existedAtBaseline(0), "The earlier batch's baseline must win");
	}

	@Test
	void mergeEarlierIgnoresCallsThatAlreadyRan() {
		backlog.add(0, pathA, false, true);
		Map<Path, HookBacklog.PendingHooks> batch = backlog.detach();
		batch.get(pathA).toRun().remove(0);
		batch.get(pathA).setExistedAtBaseline(0, false);
		backlog.mergeEarlier(batch);
		assertTrue(backlog.isEmpty(), "A cleared call must not be restored");
	}

	@Test
	void mergeEarlier_earlierPresentBaselineWins() {
		backlog.add(0, pathA, true, true);
		Map<Path, HookBacklog.PendingHooks> batch = backlog.detach();
		backlog.add(0, pathA, false, true);
		backlog.mergeEarlier(batch);
		HookBacklog.PendingHooks hooks = backlog.detach().get(pathA);
		assertTrue(hooks.existedAtBaseline(0), "The earlier batch's baseline must win");
	}
}
