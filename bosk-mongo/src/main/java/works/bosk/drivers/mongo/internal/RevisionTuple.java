package works.bosk.drivers.mongo.internal;

import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonString;
import org.jspecify.annotations.Nullable;

import static works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;

/**
 * The revision number and incarnation that describe how up-to-date a state
 * document is.
 *
 * <p>
 * The {@code incarnation} identifies one lifetime of the document's
 * {@link #revision() revision} counter. It is a UUID generated when the
 * document is created, and a new UUID is generated whenever the document is
 * re-created, which resets the revision counter to 1. A document that is
 * deleted and later re-created therefore gets an incarnation that is distinct
 * from the deleted one, even if the old document is no longer available to
 * consult.
 *
 * <p>
 * Both fields are required on every state document.
 */
record RevisionTuple(String incarnation, long revision) {
	/**
	 * @return the revision and incarnation for a newly created document
	 */
	static RevisionTuple newLifetime() {
		return new RevisionTuple(UUID.randomUUID().toString(), 1);
	}

	/**
	 * @return the revision and incarnation for the next full write of a
	 * document: a new lifetime if there was no prior document, otherwise the
	 * same incarnation with the revision incremented
	 */
	static RevisionTuple nextAfter(@Nullable RevisionTuple prior) {
		if (prior == null) {
			return newLifetime();
		} else {
			return new RevisionTuple(prior.incarnation, prior.revision + 1);
		}
	}

	/**
	 * @return true if both revisions belong to the same lifetime of the
	 * document, so their revision numbers are comparable
	 */
	boolean sameIncarnationAs(RevisionTuple other) {
		return incarnation.equals(other.incarnation);
	}

	/**
	 * @return the revision and incarnation stored in {@code document}
	 * @throws BsonInvalidOperationException if either field is missing or has
	 * the wrong type
	 */
	static RevisionTuple from(BsonDocument document) {
		BsonString incarnationValue = document.getString(DocumentFields.incarnation.name(), null);
		BsonInt64 revisionValue = document.getInt64(DocumentFields.revision.name(), null);
		if (incarnationValue == null || revisionValue == null) {
			throw new BsonInvalidOperationException("State document is missing its revision number or incarnation");
		}
		return new RevisionTuple(incarnationValue.getValue(), revisionValue.longValue());
	}

	void putInto(BsonDocument document) {
		document.put(DocumentFields.incarnation.name(), new BsonString(incarnation));
		document.put(DocumentFields.revision.name(), new BsonInt64(revision));
	}
}
