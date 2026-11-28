package works.bosk;

import org.jspecify.annotations.Nullable;

/**
 * The notification for one update: what happened, and which hooks had been
 * registered in time to hear about it.
 *
 * <p>
 * It uses the roots, update target, and diagnostic attributes captured when the
 * update was applied, not the values current when a hook runs, because a
 * standard-style hook must see the state and context of the update that
 * triggered it. The range of hook registrations is frozen at that point too, so
 * a hook registered later does not receive the notification for an earlier
 * update.
 *
 * <p>
 * Expanding the notification runs each hook in that range as it finds it, so it
 * can wait for a hook, and hence can be interrupted. Expanding it into
 * {@link HookBacklog} merely records its calls.
 */
record HookNotification<RR>(
	@Nullable RR priorRoot,
	RR rootForHook,
	Reference<?> target,
	MapValue<String> diagnosticAttributes,
	int hookStart,
	int hookEnd
) {}
