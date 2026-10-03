package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import works.bosk.drivers.mongo.MongoDriverSettings;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * The {@link MongoClientSettings} for the two {@code MongoClient}s a
 * {@link MainDriver} uses: one for queries and one for the change stream.
 * <p>
 * They are built together because they share a common core and all their
 * timeouts are derived from {@link MongoDriverSettings#timescaleMS()}.
 *
 * @param queryTimeoutMS the query client's operation timeout, exposed so
 * {@link MainDriver}'s diagnostic summary can report the value the client
 * actually uses
 */
record ClientSettings(
	MongoClientSettings query,
	long queryTimeoutMS,
	MongoClientSettings changeStream
) {
	static ClientSettings derive(MongoClientSettings base, MongoDriverSettings driverSettings) {
		long timescaleMS = driverSettings.timescaleMS();

		// The query client uses the driver's "client-side operation timeout",
		// which bounds each database operation as a whole rather than each
		// phase: server selection, connection checkout, connecting, and reading
		// the response all draw on this single budget, and the driver derives
		// each command's server-side maxTimeMS from whatever remains.
		//
		// It is deliberately the largest of the budgets, so that no single
		// phase can consume it and starve the command. Server selection gets
		// half of it (2 * timescaleMS, the same as the change-stream client)
		// and connecting gets a quarter, leaving the command at least the
		// remaining quarter.
		long queryTimeout = 4L * timescaleMS;

		MongoClientSettings common = MongoClientSettings
			.builder(base)
			.applyToServerSettings(s ->
				// If timescaleMS is shorter than the default min heartbeat,
				// then we need to reduce this setting to prevent the client
				// from using a stale view of the server state for too long.
				// If timescaleMS is longer, then the user has told us
				// they don't mind longer delays and want the increased
				// efficiency of fewer heartbeats.
				// Either way, timescaleMS is the right value for this setting.
				//
				// Note that this doesn't set the heartbeat frequency itself.
				// That is left at the default value, since it is only used
				// to "notice" connectivity problems when the driver is quiescent,
				// which is not time-critical and is not governed by timescaleMS:
				// the actual behaviour of the bosk during a network partition
				// is that its contents remain fixed, and it doesn't matter much
				// whether that is achieved by formally disconnecting or simply
				// by doing nothing.
				s.minHeartbeatFrequency(timescaleMS, MILLISECONDS))
			// By default, we deal only with durable data that won't get rolled back.
			// In some circumstances, we need the very latest possible data for correctness,
			// so we override the ReadConcern in those cases.
			.readConcern(ReadConcern.MAJORITY)
			.writeConcern(WriteConcern.MAJORITY)
			// Both clients bound the phases of an operation the same way.
			//
			// Server selection needs a budget larger than minHeartbeatFrequency
			// (see above): when it finds no server it waits that long before
			// re-checking, so its timeout must exceed it by at least a round trip,
			// or the re-check it triggers can never be observed. Two timescaleMS
			// allows two re-checks. Connecting is a single round trip.
			//
			// These are kept below the query client's operation timeout
			// (see queryTimeout above), so that no single phase can consume the
			// whole budget and starve the command.
			.applyToClusterSettings(c ->
				c.serverSelectionTimeout(2 * timescaleMS, MILLISECONDS))
			.applyToSocketSettings(s ->
				s.connectTimeout(timescaleMS, MILLISECONDS))
			.build();

		// The change-stream client can't use the driver's operation timeout:
		// an idle change-stream cursor may legitimately wait a long time
		// between events, so its socket read timeout must be 0, and that is
		// a per-client setting.
		long changeStreamReadTimeout = 0;
		MongoClientSettings changeStream = MongoClientSettings.builder(common)
			.applyToSocketSettings(s -> s.readTimeout(changeStreamReadTimeout, MILLISECONDS))
			.build();

		// Queries must not hang indefinitely, so they use the driver's
		// operation timeout (see queryTimeout above), which is deliberately
		// larger than the phase budgets above.
		MongoClientSettings query = MongoClientSettings.builder(common)
			.timeout(queryTimeout, MILLISECONDS)
			.build();

		return new ClientSettings(query, queryTimeout, changeStream);
	}
}
