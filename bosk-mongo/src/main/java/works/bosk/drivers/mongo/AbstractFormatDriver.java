package works.bosk.drivers.mongo;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonValue;
import works.bosk.BoskDiagnosticContext;
import works.bosk.MapValue;
import works.bosk.RootReference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.status.BsonComparator;
import works.bosk.drivers.mongo.status.MongoStatus;
import works.bosk.drivers.mongo.status.StateStatus;

import static works.bosk.drivers.mongo.Formatter.REVISION_ZERO;

@RequiredArgsConstructor
non-sealed abstract class AbstractFormatDriver<R extends StateTreeNode> implements FormatDriver<R> {
	final RootReference<R> rootRef;
	final BoskDiagnosticContext diagnosticContext;
	final Formatter formatter;

	@Override
	public MongoStatus readStatus() {
		try {
			BsonState dbContents = loadBsonState();
			BsonDocument loadedBsonState = dbContents.state;
			BsonValue inMemoryState = formatter.object2bsonValue(rootRef.value(), rootRef.targetType());
			BsonComparator comp = new BsonComparator();
			return new MongoStatus(
				null,
				null, // MainDriver should fill this in
				new StateStatus(
					dbContents.revision.longValue(),
					formatter.bsonValueBinarySize(loadedBsonState),
					comp.difference(inMemoryState, loadedBsonState)
				)
			);
		} catch (UninitializedCollectionException e) {
			return new MongoStatus(
				e.toString(),
				null,
				null
			);
		}
	}

	@Override
	public StateAndMetadata<R> loadAllState() throws IOException, UninitializedCollectionException {
		BsonState bsonState = loadBsonState();
		if (bsonState.state() == null) {
			throw new IOException("No existing state in document");
		}

		R root = formatter.document2object(bsonState.state(), rootRef);
		BsonInt64 revision = bsonState.revision() == null ? REVISION_ZERO : bsonState.revision();
		MapValue<String> diagnosticAttributes = bsonState.diagnosticAttributes() == null
			? MapValue.empty()
			: formatter.decodeDiagnosticAttributes(bsonState.diagnosticAttributes());

		return new StateAndMetadata<>(root, revision, diagnosticAttributes);
	}

	/**
	 * Low-level read of the database contents, with only the minimum interpretation
	 * necessary to determine what the various parts correspond to.
	 *
	 * @return the contents of the database; fields of the returned
	 * record can be null if they don't exist in the database.
	 */
	abstract BsonState loadBsonState() throws UninitializedCollectionException;

	record BsonState(
		BsonDocument state,
		BsonInt64 revision,
		BsonDocument diagnosticAttributes
	){}

}
