package works.bosk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class HookRecorder {
	private final List<Event> events = new ArrayList<>();

	public List<Event> events() {
		return events;
	}

	/**
	 * Call if you want to record just the events that happen after a certain point.
	 */
	void restart() {
		events.clear();
	}

	static class Event {
		final String hookName;
		final Event.Kind kind;
		final Reference<?> scope;
		final Object value;

		Event(String hookName, Event.Kind kind, Reference<?> scope, Object value) {
			this.hookName = hookName;
			this.kind = kind;
			this.scope = scope;
			this.value = value;
		}

		enum Kind {CHANGED}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			} else if (o instanceof Event other) {
				return Objects.equals(hookName, other.hookName)
					&& kind == other.kind
					&& Objects.equals(scope, other.scope)
					&& Objects.equals(value, other.value);
			} else {
				return false;
			}
		}

		@Override
		public int hashCode() {
			return Objects.hash(hookName, kind, scope, value);
		}

		@Override
		public String toString() {
			// Start with a newline to make diffs easier to read if there are test failures
			return "\n" + kind + "(" + hookName + ":" + scope + (EVENT_STRINGS_INCLUDE_VALUE ? "=" + value : "") + ")";
		}
	}

	/**
	 * Usually the value is just too much info, but sometimes it can help diagnose errors.
	 */
	private static final boolean EVENT_STRINGS_INCLUDE_VALUE = true;

	public <T> BoskHook<T> hookNamed(String name) {
		return hookNamed(name, x -> {
		});
	}

	public <T> BoskHook<T> hookNamed(String name, BoskHook<T> body) {
		return reference -> {
			events.add(new Event(name, Event.Kind.CHANGED, reference, reference.valueIfExists()));
			body.onChanged(reference);
		};
	}
}
