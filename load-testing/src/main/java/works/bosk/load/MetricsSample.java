package works.bosk.load;

/**
 * One periodic observation of the JVM during a load run.
 *
 * @param elapsedMillis how long the measurement phase has been running
 * @param ops           how many operations have completed in the measurement phase
 * @param heapUsed      heap bytes currently in use
 * @param heapMax       maximum heap bytes, or the committed size if no maximum is set
 * @param gcCount       total garbage collections so far
 * @param gcTimeMillis  total garbage-collection time so far
 */
public record MetricsSample(
	long elapsedMillis,
	long ops,
	long heapUsed,
	long heapMax,
	long gcCount,
	long gcTimeMillis
) {
}
