package works.bosk.drivers.mongo.internal;

import org.bson.BsonInt64;
import works.bosk.MapValue;
import works.bosk.StateTreeNode;

public record StateAndMetadata<R extends StateTreeNode>(
	R state,
	BsonInt64 revision,
	MapValue<String> diagnosticAttributes
) { }
