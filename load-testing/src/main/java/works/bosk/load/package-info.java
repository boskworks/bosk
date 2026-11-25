/**
 * A load-testing harness for Bosk drivers.
 *
 * <p>The first slice runs a conflict-free workload against the in-memory driver. Each worker owns
 * a disjoint slice of the root catalog and repeatedly replaces the {@code string} field of its
 * entities, so the final state is deterministic and can be verified exactly.
 *
 * <p>One run has a warmup phase and a measurement phase. During measurement the harness records
 * submit and flush latency in per-worker HdrHistograms, samples heap and GC once per second, and
 * writes a summary plus a time series to CSV. The runner does not ramp by itself; a ramp is a
 * sequence of runs with different {@code --threads} values, each in a fresh process.
 *
 * <p>The module is deliberately separate from the published libraries. It is not shipped. It
 * supports the in-memory driver and the Mongo driver in SEQUOIA and Pando formats; SQL driver
 * support, fault injection, and state-shape knobs are still to come.
 */
package works.bosk.load;
