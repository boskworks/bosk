package works.bosk.drivers.mongo.internal;

import org.bson.conversions.Bson;

/**
 * The query-shaping options of a read: an optional sort, an optional maximum
 * number of documents, and an optional projection. A {@code limit} of 0 means
 * no limit, following the Mongo driver's convention.
 */
record ReadOptions(Bson sort, int limit, Bson projection) {
	static ReadOptions none() {
		return new ReadOptions(null, 0, null);
	}

	ReadOptions withSort(Bson sort) {
		return new ReadOptions(sort, limit, projection);
	}

	ReadOptions withLimit(int limit) {
		return new ReadOptions(sort, limit, projection);
	}

	ReadOptions withProjection(Bson projection) {
		return new ReadOptions(sort, limit, projection);
	}
}
