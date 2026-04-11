/**
 * Logback-specific logging utilities.
 * <p>
 * Provides two features:
 * <ol>
 *   <li>
 *     <b>Per-bosk log control:</b> Suppresses expected warnings during testing.
 *   </li>
 *   <li>
 *     <b>Log replay on failure:</b> Captures log events that would otherwise be filtered out,
 *     and replays them when a test fails.
 *   </li>
 * </ol>
 *
 * <h2>Per-bosk log control</h2>
 * <p>
 * {@link works.bosk.logback.BoskLogFilter} provides per-bosk logging control, intended to suppress expected warnings
 * and errors during testing.
 * <p>
 * A {@link works.bosk.Bosk Bosk} whose driver stack includes {@link works.bosk.logback.BoskLogFilter#withController}
 * can set log levels using {@link works.bosk.logback.BoskLogFilter.LogController#setLogging LogController.setLogging} without affecting other logs.
 * The driver sets the MDC key {@link works.bosk.logging.MdcKeys#BOSK_INSTANCE_ID BOSK_INSTANCE_ID},
 * and the filter uses that to determine the correct {@code LogController} object to adjust log levels for that bosk.
 *
 * <h2>Log replay on failure</h2>
 * <p>
 * {@link works.bosk.logback.ReplayLogsOnFailureExtension} is a JUnit extension
 * that captures and replays log events when a test fails.
 * <p>
 * <b>How it works:</b>
 * <ol>
 *   <li>
 *     <b>RecordingTurboFilter</b> sits in the Logback filter chain and records log events
 *     that match its configured level, storing them in a per-test buffer.
 *   </li>
 *   <li>
 *     <b>ReplayLogsOnFailureExtension</b> coordinates with {@code RecordingTurboFilter}.
 *     Before each test, it sets an MDC key identifying the test.
 *     After each test, if the test failed, it retrieves the recorded events and prints them.
 *   </li>
 *   <li>
 *     <b>ReplayLogsOnFailure</b> is the annotation that enables and configures this behavior
 *     on a test class.
 *   </li>
 * </ol>
 * <p>
 * <b>Quick start:</b>
 * <ol>
 *   <li>
 *     Add the filter to your logback-test.xml (or logback.xml):
 *     <pre>
 *     &lt;turboFilter class="works.bosk.logback.RecordingTurboFilter"/&gt;
 *     </pre>
 *   </li>
 *   <li>
 *     Add {@code @ReplayLogsOnFailure} to your test class:
 *     <pre>
 *     &#64;ReplayLogsOnFailure
 *     class MyTest { ... }
 *     </pre>
 *   </li>
 * </ol>
 * <p>
 * When a test fails, you'll see the captured log events printed to help diagnose the failure.
 * <p>
 * <b>For detailed configuration options</b> (level, capacity, routing key for multi-threaded tests),
 * see {@link works.bosk.logback.RecordingTurboFilter}.
 *
 * @see works.bosk.logback.ReplayLogsOnFailure
 * @see works.bosk.logback.RecordingTurboFilter
 * @see works.bosk.logback.ReplayLogsOnFailureExtension
 * @see works.bosk.logback.BoskLogFilter
 */
package works.bosk.logback;
