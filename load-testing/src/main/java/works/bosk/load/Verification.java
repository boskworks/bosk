package works.bosk.load;

/**
 * The outcome of checking the final state against what the workload expected.
 *
 * @param checked the number of targets examined
 * @param failed  the number of targets whose value did not match
 * @param message details of the first failure, or {@code null} when everything matched
 */
public record Verification(int checked, int failed, String message) {

	public static Verification matched(int checked) {
		return new Verification(checked, 0, null);
	}

	public static Verification failed(int checked, int failed, String message) {
		return new Verification(checked, failed, message);
	}

	public boolean matched() {
		return failed == 0;
	}
}
