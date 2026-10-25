package works.bosk;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coalesces pending hook notifications during overload mode.
 *
 * <p>
 * Each path keeps one bit per hook registration. A hook's first pending
 * notification records whether the reference existed before that notification.
 * If a reference is created and then deleted before the hook runs, the pending
 * bit is removed: the hook never observed the reference and has nothing to do.
 *
 * <p>
 * Coalesced notifications do not carry diagnostic attributes because there is
 * no single originating update for them.
 */
final class OverloadPendingSet {
	private Map<Path, PendingHooks> pending = new LinkedHashMap<>();
	private int size;

	/**
	 * Adds a pending hook notification.
	 *
	 * @param hookIndex     the registration's stable index
	 * @param path          the reference that changed
	 * @param existedBefore whether the reference existed before this notification
	 * @param existsAfter   whether the reference exists after this notification
	 */
	void add(int hookIndex, Path path, boolean existedBefore, boolean existsAfter) {
		assert existedBefore || existsAfter : "A hook notification must describe an actual change";
		PendingHooks hooks = pending.get(path);
		if (hooks == null) {
			hooks = new PendingHooks(new BitSet(), new BitSet());
			pending.put(path, hooks);
		}
		if (hooks.toRun().get(hookIndex)) {
			if (hooks.existedAtBaseline().get(hookIndex) || existsAfter) {
				return;
			}
			// The hook never saw this reference: it was absent at baseline and is gone now.
			hooks.toRun().clear(hookIndex);
			hooks.existedAtBaseline().clear(hookIndex);
			size--;
			if (hooks.toRun().isEmpty()) {
				pending.remove(path);
			}
		} else {
			hooks.toRun().set(hookIndex);
			hooks.existedAtBaseline().set(hookIndex, existedBefore);
			size++;
		}
	}

	boolean isEmpty() {
		return pending.isEmpty();
	}

	int size() {
		return size;
	}

	/**
	 * Adds any still-pending notifications from a detached batch back into this set.
	 * This is used when a batch is interrupted before all its hooks have run.
	 *
	 * <p>
	 * If the target already has a notification for the same pair, the source
	 * baseline wins because the source was detached first, so its baseline is
	 * the earliest observation.
	 */
	void merge(Map<Path, PendingHooks> batch) {
		for (var entry : batch.entrySet()) {
			PendingHooks source = entry.getValue();
			if (source.toRun().isEmpty()) {
				continue;
			}
			PendingHooks target = pending.computeIfAbsent(entry.getKey(), _ -> new PendingHooks(new BitSet(), new BitSet()));
			for (int hookIndex = source.toRun().nextSetBit(0); hookIndex >= 0; hookIndex = source.toRun().nextSetBit(hookIndex + 1)) {
				if (!target.toRun().get(hookIndex)) {
					size++;
				}
				target.toRun().set(hookIndex);
				target.existedAtBaseline().set(hookIndex, source.existedAtBaseline().get(hookIndex));
			}
		}
	}

	/**
	 * Returns the current pending notifications and starts a fresh set.
	 * The returned map is no longer modified by this object.
	 */
	Map<Path, PendingHooks> detach() {
		Map<Path, PendingHooks> result = pending;
		pending = new LinkedHashMap<>();
		size = 0;
		return result;
	}

	record PendingHooks(BitSet toRun, BitSet existedAtBaseline) {}
}
