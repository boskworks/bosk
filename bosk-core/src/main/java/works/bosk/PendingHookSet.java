package works.bosk;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pending hook calls, with one entry per hook registration and reference.
 *
 * <p>
 * Each path keeps the set of hook registrations with a pending call, and the
 * registrations whose reference did not exist at their first pending change.
 * A registration is nonexistent at baseline when the hook was first notified
 * of a creation; if the reference is created and then deleted before the hook
 * runs, that registration is removed: the hook has nothing to do.
 *
 * <p>
 * Notifications accumulate in the <em>active</em> set until a batch detaches
 * them. The detached set is delivered outside this object's monitor, while the
 * next active set accepts notifications for changes that arrive during
 * delivery.
 *
 * <p>
 * Compacted notifications do not carry diagnostic attributes because there is
 * no single originating update for them.
 */
final class PendingHookSet {
	private Map<Path, PendingHooks> active = new LinkedHashMap<>();

	/**
	 * Adds a pending hook notification.
	 *
	 * @param hookIndex     the registration's stable index, as reported by
	 *                      {@link HookRunner.HookRegistration#index()}
	 * @param path          the path of the reference that changed
	 * @param existedBefore whether the reference existed before the change, as
	 *                      {@link Reference#exists()} would have reported
	 * @param existsAfter   whether the reference exists after the change
	 */
	synchronized void add(int hookIndex, Path path, boolean existedBefore, boolean existsAfter) {
		assert existedBefore || existsAfter : "A hook notification must describe an actual change";
		PendingHooks hooks = active.get(path);
		if (hooks == null) {
			hooks = new PendingHooks();
			active.put(path, hooks);
		}
		if (hooks.toRun().contains(hookIndex)) {
			if (hooks.existedAtBaseline(hookIndex) || existsAfter) {
				return;
			}
			// The hook never saw this reference: it was absent at baseline and is gone now.
			hooks.toRun().remove(hookIndex);
			hooks.setExistedAtBaseline(hookIndex, false);
			if (hooks.toRun().isEmpty()) {
				active.remove(path);
			}
		} else {
			hooks.toRun().add(hookIndex);
			hooks.setExistedAtBaseline(hookIndex, existedBefore);
		}
	}

	synchronized boolean isEmpty() {
		return active.isEmpty();
	}

	/**
	 * Adds notifications that were observed earlier than the ones already in this
	 * set, as when a batch is put back after delivery failed.
	 *
	 * <p>
	 * If this set already has a notification for the same pair, the earlier batch's
	 * baseline wins, because its observation came first.
	 */
	synchronized void mergeEarlier(Map<Path, PendingHooks> earlierBatch) {
		for (var entry : earlierBatch.entrySet()) {
			PendingHooks earlier = entry.getValue();
			if (earlier.toRun().isEmpty()) {
				continue;
			}
			PendingHooks target = active.computeIfAbsent(entry.getKey(), _ -> new PendingHooks());
			earlier.toRun().forEachInt(hookIndex -> {
				target.toRun().add(hookIndex);
				target.setExistedAtBaseline(hookIndex, earlier.existedAtBaseline(hookIndex));
			});
		}
	}

	/**
	 * Detaches the active notifications for delivery and starts a fresh active
	 * set. The returned map is no longer modified by this object.
	 */
	synchronized Map<Path, PendingHooks> detach() {
		Map<Path, PendingHooks> result = active;
		active = new LinkedHashMap<>();
		return result;
	}

	synchronized void clear() {
		active = new LinkedHashMap<>();
	}

	static final class PendingHooks {
		private final IntSet toRun = new IntSet();
		/**
		 * The registrations whose reference did not exist at baseline, or
		 * {@code null} if none were. This can include registrations that are no
		 * longer pending; a later notification overwrites the entry.
		 *
		 * <p>
		 * Null stands for the empty set. That is not the usual style in this
		 * codebase, but an absent baseline is the uncommon case, and this avoids
		 * allocating an empty {@code IntSet} for every changed reference.
		 */
		@Nullable private IntSet nonexistentAtBaseline;

		IntSet toRun() {
			return toRun;
		}

		/**
		 * @return whether the reference existed before this hook's first pending
		 * change, which is true unless the hook was first notified of a creation
		 */
		boolean existedAtBaseline(int hookIndex) {
			return nonexistentAtBaseline == null || !nonexistentAtBaseline.contains(hookIndex);
		}

		/**
		 * Records whether the reference existed before this hook's first pending
		 * change.
		 */
		void setExistedAtBaseline(int hookIndex, boolean existed) {
			if (existed) {
				if (nonexistentAtBaseline != null) {
					nonexistentAtBaseline.remove(hookIndex);
					if (nonexistentAtBaseline.isEmpty()) {
						nonexistentAtBaseline = null;
					}
				}
			} else {
				if (nonexistentAtBaseline == null) {
					nonexistentAtBaseline = new IntSet();
				}
				nonexistentAtBaseline.add(hookIndex);
			}
		}
	}
}
