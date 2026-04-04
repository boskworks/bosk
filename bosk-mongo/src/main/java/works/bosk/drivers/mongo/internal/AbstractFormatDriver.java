package works.bosk.drivers.mongo.internal;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;
import works.bosk.BoskContext;
import works.bosk.MapValue;
import works.bosk.RootReference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;
import works.bosk.drivers.mongo.status.BsonComparator;
import works.bosk.drivers.mongo.status.MongoStatus;
import works.bosk.drivers.mongo.status.StateStatus;

import static works.bosk.drivers.mongo.internal.Formatter.REVISION_ZERO;

@RequiredArgsConstructor
abstract non-sealed class AbstractFormatDriver<R extends StateTreeNode> implements FormatDriver<R> {
	final RootReference<R> rootRef;
	final BoskContext context;
	final Formatter formatter;

	@Override
	public MongoStatus readStatus() {
		try {
			BsonStateAndMetadata dbContents = loadBsonStateAndMetadata();
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
		BsonStateAndMetadata bsonStateAndMetadata = loadBsonStateAndMetadata();
		if (bsonStateAndMetadata.state() == null) {
			throw new IOException("No existing state in document");
		}

		R root = formatter.document2object(bsonStateAndMetadata.state(), rootRef);
		BsonInt64 revision = bsonStateAndMetadata.revision() == null ? REVISION_ZERO : bsonStateAndMetadata.revision();
		MapValue<String> diagnosticAttributes = bsonStateAndMetadata.diagnosticAttributes() == null
			? MapValue.empty() // It's not clear what missing attributes mean, but using null here would have the effect of leaving the old attributes in place, which seems flaky
			: formatter.decodeDiagnosticAttributes(bsonStateAndMetadata.diagnosticAttributes());

		return new StateAndMetadata<>(root, revision, diagnosticAttributes);
	}

	/**
	 * Low-level read of the database contents, with only the minimum interpretation
	 * necessary to determine what the various parts correspond to.
	 *
	 * @return the contents of the database; fields of the returned
	 * record can be null if they don't exist in the database.
	 */
	abstract BsonStateAndMetadata loadBsonStateAndMetadata() throws UninitializedCollectionException;

	protected BsonDocument blankUpdateDoc() {
		return new BsonDocument()
			.append("$inc", new BsonDocument(DocumentFields.revision.name(), new BsonInt64(1)))
			.append("$set", new BsonDocument()
				.append(
					DocumentFields.diagnostics.name(),
					formatter.encodeDiagnostics(context.getAttributes())
				)
				.append(
					DocumentFields.tenant.name(),
					formatter.encodeTenant(context.getEstablishedTenant())
				)
			);
	}

	protected BsonDocument initialDocument(BsonValue initialState, BsonInt64 revision, BsonString documentId) {
		BsonDocument fieldValues = new BsonDocument("_id", documentId);

		fieldValues.put(DocumentFields.path.name(), new BsonString("/"));
		fieldValues.put(DocumentFields.state.name(), initialState);
		fieldValues.put(DocumentFields.revision.name(), revision);
		fieldValues.put(DocumentFields.tenant.name(), formatter.encodeMaybeTenant(context.getTenant()));
		fieldValues.put(DocumentFields.diagnostics.name(), formatter.encodeDiagnostics(context.getAttributes()));

		return fieldValues;
	}

	/**
	 * Low-level version of {@link StateAndMetadata}.
	 */
	record BsonStateAndMetadata(
		BsonDocument state,
		BsonInt64 revision,
		BsonDocument diagnosticAttributes
	){}

}
