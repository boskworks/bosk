package works.bosk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pending hook calls, with one bucket per hook registration.
 *
 * <p>
 * Each bucket maps a path to whether the reference existed before that hook's
 * first pending notification for it. Later notifications leave the baseline
 * alone. If a reference is created and then deleted before the hook runs, the
 * entry is removed: the hook never observed the reference and has nothing to do.
 *
 * <p>
 * A bucket is never empty. It appears when the hook's first notification
 * arrives and disappears when its last entry goes. Hooks are visited in index
 * order, which is registration order, and each hook visits its paths in the
 * order it first heard about them.
 *
 * <p>
 * Grouping the notifications by hook, rather than by path, keeps the memory
 * proportional to the number of pending notifications. Grouping them by path
 * would make it proportional to the number of changed references times the
 * number of hook registrations, because each path would need to record which
 * hooks are pending for it.
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
	private Map<Integer, PendingPaths> active = new TreeMap<>();

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
		PendingPaths bucket = active.get(hookIndex);
		if (bucket == null) {
			PendingPaths started = new PendingPaths();
			started.put(path, existedBefore);
			active.put(hookIndex, started);
		} else if (Boolean.FALSE.equals(bucket.putIfAbsent(path, existedBefore)) && !existsAfter) {
			// The hook never saw this reference: it was absent at baseline and is gone now.
			bucket.remove(path);
			if (bucket.isEmpty()) {
				active.remove(hookIndex);
			}
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
	 * If this set already has a notification for the same hook and path, the
	 * earlier batch's baseline wins, because its observation came first.
	 */
	synchronized void mergeEarlier(Map<Integer, PendingPaths> earlierBatch) {
		for (var entry : earlierBatch.entrySet()) {
			PendingPaths earlier = entry.getValue();
			if (earlier.isEmpty()) {
				// Only possible if the batch had been partly delivered, so the
				// remaining notifications have all run.
				continue;
			}
			PendingPaths target = active.get(entry.getKey());
			if (target == null) {
				active.put(entry.getKey(), new PendingPaths(earlier));
			} else {
				target.putAll(earlier);
			}
		}
	}

	/**
	 * Detaches the active notifications for delivery and starts a fresh active
	 * set. The returned map is no longer modified by this object.
	 */
	synchronized Map<Integer, PendingPaths> detach() {
		Map<Integer, PendingPaths> result = active;
		active = new TreeMap<>();
		return result;
	}

	synchronized void clear() {
		active = new TreeMap<>();
	}

	/**
	 * Pending compacted notifications for one hook registration.
	 *
	 * <p>
	 * Each entry maps a changed path to whether the reference existed before the
	 * earliest pending change to it.
	 */
	static final class PendingPaths extends LinkedHashMap<Path, Boolean> {
		PendingPaths() {
		}

		PendingPaths(Map<Path, Boolean> source) {
			super(source);
		}

		private static final long serialVersionUID = 1L;
	}
}
