package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.result.UpdateResult;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.RootReference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;
import works.bosk.drivers.mongo.status.BsonComparator;
import works.bosk.drivers.mongo.status.MongoStatus;
import works.bosk.drivers.mongo.status.StateStatus;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;

import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static com.mongodb.client.model.changestream.OperationType.REPLACE;
import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;
import static works.bosk.drivers.mongo.internal.BsonFormatter.dottedFieldNameOf;
import static works.bosk.drivers.mongo.internal.Formatter.REVISION_ZERO;

@RequiredArgsConstructor
abstract non-sealed class AbstractFormatDriver<R extends StateTreeNode> implements FormatDriver<R> {
	final RootReference<R> rootRef;
	final BoskContext context;
	final Formatter formatter;
	final TransactionalCollection collection;
	final BoskDriver downstream;
	final FlushLock flushLock;

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

	protected void logNonexistentField(String dottedName, InvalidTypeException e) {
		LOGGER.trace("Nonexistent field {}", dottedName, e);
		if (LOGGER.isWarnEnabled() && ALREADY_WARNED.add(dottedName)) {
			LOGGER.warn("Ignoring updates of nonexistent field {}", dottedName);
		}
	}

	protected <T> BsonDocument replacementDoc(Reference<T> target, BsonValue value, Reference<?> startingRef) {
		String key = dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Set field {}: {}", key, value);
		BsonDocument result = blankUpdateDoc();
		result.compute("$set", (_, existing) -> {
			if (existing == null) {
				return new BsonDocument(key, value);
			} else {
				return existing.asDocument().append(key, value);
			}
		});
		return result;
	}

	protected <T> BsonDocument deletionDoc(Reference<T> target, Reference<?> startingRef) {
		String key = dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Unset field {}", key);
		return blankUpdateDoc().append("$unset", new BsonDocument(key, BsonNull.VALUE));
	}

	protected volatile BsonInt64 revisionToSkip = null;

	protected boolean shouldSkip(BsonInt64 revision) {
		return revision != null && revisionToSkip != null
			&& revision.longValue() <= revisionToSkip.longValue();
	}

	@Override
	public void onRevisionToSkip(BsonInt64 revision) {
		LOGGER.debug("+ onRevisionToSkip({})", revision.longValue());
		revisionToSkip = revision;
		flushLock.finishedRevision(revision);
	}

	/**
	 * We're required to cope with anything we might ourselves do in {@link #initializeCollection},
	 * but outside that, we want to be as strict as possible
	 * so incompatible database changes don't go unnoticed.
	 */
	protected void validateManifestEvent(ChangeStreamDocument<BsonDocument> event, Manifest effectiveManifest) throws UnprocessableEventException {
		LOGGER.debug("onManifestEvent({})", event.getOperationType().name());
		if (event.getOperationType() == INSERT || event.getOperationType() == REPLACE) {
			BsonDocument manifestDoc = requireNonNull(event.getFullDocument());
			Manifest manifest;
			try {
				manifest = formatter.decodeManifest(manifestDoc);
			} catch (UnrecognizedFormatException e) {
				throw new UnprocessableEventException("Invalid manifest", e, event.getOperationType());
			}
			if (!manifest.equals(effectiveManifest)) {
				throw new UnprocessableEventException("Manifest indicates format has changed", event.getOperationType());
			}
		} else {
			// We always use INSERT/REPLACE to update the manifest;
			// anything else is unexpected.
			throw new UnprocessableEventException("Unexpected change to manifest document", event.getOperationType());
		}
		LOGGER.debug("Ignoring benign manifest change event");
	}

	protected abstract BsonInt64 readRevisionNumber() throws FlushFailureException;

	@Override
	public void flush() throws IOException, InterruptedException {
		flushLock.awaitRevision(readRevisionNumber());
		LOGGER.debug("| Flush downstream");
		downstream.flush();
	}

	@Override
	public void close() {
		LOGGER.debug("+ close()");
		flushLock.close();
	}

	protected void writeManifest(Manifest manifest) {
		BsonDocument doc = new BsonDocument("_id", MainDriver.MANIFEST_ID);
		doc.putAll((BsonDocument) formatter.object2bsonValue(manifest, Manifest.class));
		BsonDocument filter = new BsonDocument("_id", MainDriver.MANIFEST_ID);
		LOGGER.debug("| Initial manifest: {}", doc);
		ReplaceOptions options = new ReplaceOptions().upsert(true);
		UpdateResult result = collection.replaceOne(filter, doc, options);
		LOGGER.debug("| Manifest result: {}", result);
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

	private static final Set<String> ALREADY_WARNED = newSetFromMap(new ConcurrentHashMap<>());
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractFormatDriver.class);

}
