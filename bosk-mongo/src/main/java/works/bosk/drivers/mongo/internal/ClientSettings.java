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
	MongoClientSettings changeStream,
	long changeStreamMaxAwaitTimeMS
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

		// The change-stream client can't use the driver's operation timeout,
		// because on a change stream an operation timeout is a CURSOR_LIFETIME
		// budget that would cap the cursor's life. Instead the server is asked to
		// answer each getMore within one poll interval, and the socket read
		// timeout is a multiple of that interval: a healthy idle stream always
		// answers within one interval, so only a silently dead socket trips it.
		long changeStreamMaxAwaitTimeMS = timescaleMS;
		long changeStreamReadTimeout = 2 * changeStreamMaxAwaitTimeMS;

		MongoClientSettings common = MongoClientSettings
			.builder(base)
			.applyToServerSettings(s ->
				// Both heartbeat settings track timescaleMS, so the client
				// notices changes in the server's state on roughly the timescale
				// the user asked for. (At the 10s production default,
				// heartbeatFrequency is unchanged from the driver's own default.)
				//
				// minHeartbeatFrequency is the floor on how often server selection
				// re-checks a server it believes to be down.
				//
				// heartbeatFrequency is how often the monitor checks a server it
				// believes to be healthy. It matters because a change stream whose
				// socket has silently died only surfaces once the monitor marks the
				// server unknown and server selection then fails; until that point
				// the driver's change-stream resumption keeps retrying in silence.
				s.heartbeatFrequency(timescaleMS, MILLISECONDS)
					.minHeartbeatFrequency(timescaleMS, MILLISECONDS))
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

		MongoClientSettings changeStream = MongoClientSettings.builder(common)
			.applyToSocketSettings(s -> s.readTimeout(changeStreamReadTimeout, MILLISECONDS))
			.build();

		// Queries must not hang indefinitely, so they use the driver's operation
		// timeout, which is deliberately larger than the phase budgets above.
		MongoClientSettings query = MongoClientSettings.builder(common)
			.timeout(queryTimeout, MILLISECONDS)
			.build();

		return new ClientSettings(query, queryTimeout, changeStream, changeStreamMaxAwaitTimeMS);
	}
}
