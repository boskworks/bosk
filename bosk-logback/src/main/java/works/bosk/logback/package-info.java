/**
 * Logback-specific logging utilities.
 * <p>
 * Provides the following features:
 * <ol>
 *   <li>
 *     <b>Per-bosk log suppression:</b> Suppresses expected warnings during testing.
 *   </li>
 *   <li>
 *     <b>Per-thread log suppression:</b> Suppresses expected events emitted by a test's own thread.
 *   </li>
 *   <li>
 *     <b>Log replay on failure:</b> Captures log events that would otherwise be filtered out,
 *     and replays them when a test fails.
 *   </li>
 * </ol>
 *
 * <h2>Per-bosk log suppression</h2>
 * <p>
 * {@link works.bosk.logback.BoskLogFilter} provides per-bosk log suppression, intended to suppress expected warnings
 * and errors during testing.
 * <p>
 * A {@link works.bosk.Bosk Bosk} whose driver stack includes {@link works.bosk.logback.BoskLogFilter#withController}
 * can suppress expected events using {@link works.bosk.logback.BoskLogFilter.LogController#suppressLogging LogController.suppressLogging} without affecting other logs.
 * The driver sets the MDC key {@link works.bosk.logging.MdcKeys#BOSK_INSTANCE_ID BOSK_INSTANCE_ID},
 * and the filter uses that to determine the correct {@code LogController} object to adjust log levels for that bosk.
 *
 * <h2>Per-thread log suppression</h2>
 * <p>
 * {@link works.bosk.logback.SuppressLogs @SuppressLogs} suppresses expected log events
 * emitted by the thread running a test, which is where framework logging such as
 * application startup failures comes from.
 * <pre>
 * &#64;SuppressLogs(ChattyThing.class)
 * class MyTest { ... }
 * </pre>
 * Suppression is scoped to the test's thread, so parallel tests do not interfere with each other.
 * Events emitted by other threads are not affected; use the per-bosk control above
 * for logs that a bosk emits from its own threads.
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
 * @see works.bosk.logback.SuppressLogs
 */
package works.bosk.logback;
