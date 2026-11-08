package works.bosk.drivers.mongo.internal;

import java.util.Map;
import java.util.Optional;
import org.bson.BsonString;
import works.bosk.StateTreeNode;

/**
 * The state loaded from (or written to) the database, together with the
 * metadata the driver needs to track it.
 *
 * @param documentRevisions every state document's revision number, keyed by
 * document ID
 */
public record StateAndMetadata<R extends StateTreeNode>(
	R state,
	Optional<BsonString> epoch,
	Map<BsonString, RevisionTuple> documentRevisions
) { }
