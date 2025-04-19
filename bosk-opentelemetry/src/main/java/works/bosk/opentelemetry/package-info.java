/**
 * Provides OpenTelemetry context propagation for Bosk.
 * <p>
 * To use this, configure your bosk instance to do the following two things:
 * <ol>
 *     <li>
 *         Use {@link works.bosk.opentelemetry.OpenTelemetryDriver#wrapping} on any driver
 *         that does not implicitly propagate thread context by calling its downstream driver
 *         synchronously on the same thread.
 *     </li>
 *     <li>
 *         Use {@link works.bosk.opentelemetry.OpenTelemetryRegistrar#factory()}.
 *     </li>
 * </ol>
 */
package works.bosk.opentelemetry;
