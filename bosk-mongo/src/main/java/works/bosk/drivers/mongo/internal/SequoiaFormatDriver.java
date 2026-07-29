package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.Identifier;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;
import works.bosk.exceptions.InvalidTypeException;

import static org.bson.BsonBoolean.FALSE;
import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static works.bosk.drivers.mongo.internal.BsonFormatter.dottedFieldNameOf;
import static works.bosk.drivers.mongo.internal.BsonFormatter.referenceTo;
import static works.bosk.drivers.mongo.internal.Formatter.REVISION_ZERO;

/**
 * Implements the {@link MongoDriverSettings.DatabaseFormat#SEQUOIA Sequoia} format.
 */
final class SequoiaFormatDriver<R extends StateTreeNode> extends AbstractFormatDriver<R> {
	private final String description;

	static final BsonString DOCUMENT_ID = new BsonString("boskDocument");

	SequoiaFormatDriver(
		BoskInfo<R> boskInfo,
		TransactionalCollection collection,
		MongoDriverSettings driverSettings,
		BsonSerializer bsonSerializer,
		long flushTimeoutMS,
		BoskDriver downstream
	) {
		super(
			boskInfo.rootReference(),
			boskInfo.context(),
			new Formatter(boskInfo, bsonSerializer),
			collection,
			downstream,
			flushTimeoutMS
		);
		this.description = getClass().getSimpleName() + ": " + driverSettings;
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		doUpdate(replacementDoc(target, newValue), standardPreconditions(target));
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		BsonDocument filter = standardPreconditions(target);
		filter.put(dottedFieldNameOf(target, rootRef), new BsonDocument("$exists", FALSE));
		if (doUpdate(replacementDoc(target, newValue), filter)) {
			LOGGER.debug("| Object initialized");
		} else {
			LOGGER.debug("| No update");
		}
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		doUpdate(deletionDoc(target, rootRef), standardPreconditions(target));
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		doUpdate(
			replacementDoc(target, newValue),
			explicitPreconditions(target, precondition, requiredValue));
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		doUpdate(
			deletionDoc(target, rootRef),
			explicitPreconditions(target, precondition, requiredValue));
	}

	@Override
	BsonStateAndMetadata readBsonStateAndMetadata() throws InvalidCollectionContentsException {
		try (MongoCursor<BsonDocument> cursor = collection
			.findLatest(documentFilter()) // The revision field needs to be the latest
			.limit(1)
			.cursor()
		) {
			BsonDocument document = cursor.next();
			return new BsonStateAndMetadata(
				document.getString("_id"),
				document.getInt64(DocumentFields.revision.name()),
				Formatter.getDiagnosticAttributesIfAny(document),
				document.getDocument(DocumentFields.state.name())
			);
		} catch (NoSuchElementException e) {
			throw new InvalidCollectionContentsException(SEQUOIA, "State document not found: " + DOCUMENT_ID, e);
		} catch (BsonInvalidOperationException e) {
			throw new InvalidCollectionContentsException(SEQUOIA, "State document is missing required fields: " + DOCUMENT_ID, e);
		}

	}

	@Override
	@NonNull BsonInt64 readRevisionNumberToFlush() throws RevisionFieldDisruptedException {
		LOGGER.debug("readRevisionNumberToFlush");
		try {
			try (MongoCursor<BsonDocument> cursor = revisionDocumentCursor()) {
				// Our revisionDocumentCursor matches only one document
				return cursor.next().getInt64(DocumentFields.revision.name(), REVISION_ZERO);
			}
		} catch (NoSuchElementException e) {
			throw new RevisionFieldDisruptedException("No root documents found", e);
		} catch (RuntimeException e) {
			throw new RevisionFieldDisruptedException(e);
		}
	}

	@Override
	public void initializeCollection(StateAndMetadata<R> priorContents) {
		replaceFlushLock(priorContents.revision());

		// Sequoia has only one document
		BsonValue initialState = formatter.object2bsonValue(priorContents.state(), rootRef.targetType());
		BsonInt64 newRevision = new BsonInt64(1 + priorContents.revision().longValue());
		try (var _ = context.withOnly(priorContents.diagnosticAttributes())) {
			BsonDocument update = new BsonDocument("$set", initialDocument(initialState, newRevision, DOCUMENT_ID));
			BsonDocument filter = documentFilter();
			UpdateOptions options = new UpdateOptions().upsert(true);
			LOGGER.debug("** Initial upsert for {}", DOCUMENT_ID);
			LOGGER.trace("| Filter: {}", filter);
			LOGGER.trace("| Update: {}", update);
			LOGGER.trace("| Options: {}", options);
			UpdateResult result = collection.updateOne(filter, update, options);
			LOGGER.debug("| Result: {}", result);
		}

		// This is the only time Sequoia changes two documents for the same operation.
		// Aside from refurbish, it's the only reason we'd want multi-document transactions,
		// and it's not even a strong reason, because this still works correctly
		// if interpreted as two separate events.
		writeManifest(Manifest.forSequoia());

		// Update the state that we "know about"
		finishedRevision(newRevision);
	}

	/**
	 * We're required to cope with anything we might ourselves do in {@link FormatDriver#initializeCollection}.
	 */
	@Override
	public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		fieldTracker.processEvent(event);
		assert event.getDocumentKey() != null;
		if (isManifestID(event.getDocumentKey().get("_id"))) {
			/* We're required to cope with anything we might ourselves do in {@link #initializeCollection},
			 * but outside that, we want to be as strict as we can
			 * so incompatible database changes don't go unnoticed.
			 */
			validateManifestEvent(event, Manifest.forSequoia());
			return;
		}
		if (!DOCUMENT_FILTER.equals(event.getDocumentKey())) {
			LOGGER.debug("Ignoring event for unrecognized document key: {}", event.getDocumentKey());
			return;
		}
		switch (event.getOperationType()) {
			case INSERT: case REPLACE: {
				// Note: an INSERT could be coming from this very bosk initializing the collection,
				// in which case replacing the entire bosk state downstream is unnecessary.
				// However, it could also be coming from another bosk, or even a human operator doing
				// a repair, so we can't ignore it either.
				// It seems unavoidable to pass the change downstream.
				BsonDocument fullDocument = event.getFullDocument();
				if (fullDocument == null) {
					// The MongoDB docs are confusing, but it seems there should
					// always be a fullDocument for INSERT events, and probably
					// also REPLACE. That would imply that this case is impossible.
					throw new UnprocessableEventException("Missing fullDocument", event.getOperationType());
				}
				BsonString docId = event.getDocumentKey().getString("_id");
				BsonDocument attrsBson = fieldTracker.getFieldAsDocument(docId, DocumentFieldTracker.TrackedField.DIAGNOSTICS);
				MapValue<String> diagnosticAttributes = attrsBson == null ? MapValue.empty() : formatter.decodeDiagnosticAttributes(attrsBson);
				try (
					var _ = context.withOnly(diagnosticAttributes)
				) {
					BsonInt64 revision = formatter.getRevisionFromFullDocument(fullDocument);
					BsonDocument state = fullDocument.getDocument(DocumentFields.state.name(), null);
					if (state == null) {
						throw new UnprocessableEventException("Missing state field", event.getOperationType());
					}
					R newRoot = formatter.document2object(state, rootRef);
					// Note that we do not check revisionToSkip here. We probably should... but this actually
					// saves us in MongoDriverResiliencyTest.documentReappears_recovers because when the doc
					// disappears, we don't null out revisionToSkip. TODO: Rethink what's the right way to handle this.
					LOGGER.debug("| Replace {}", rootRef);
					downstream.submitReplacement(rootRef, newRoot);
					finishedRevision(revision);
				}
			} break;
			case UPDATE: {
				UpdateDescription updateDescription = event.getUpdateDescription();
				if (updateDescription != null) {
					BsonInt64 revision = formatter.getRevisionFromUpdateEvent(event);
					BsonString updateDocId = event.getDocumentKey().getString("_id");
					BsonDocument updateAttrsBson = fieldTracker.getFieldAsDocument(updateDocId, DocumentFieldTracker.TrackedField.DIAGNOSTICS);
					MapValue<String> diagnosticAttributes = updateAttrsBson == null ? MapValue.empty() : formatter.decodeDiagnosticAttributes(updateAttrsBson);
					try (
						var _ = context.withOnly(diagnosticAttributes)
					) {
						if (shouldSkip(revision)) {
							LOGGER.debug("Skipping revision {}", revision.longValue());
							return;
						}

						replaceUpdatedFields(updateDescription.getUpdatedFields());
						deleteRemovedFields(updateDescription.getRemovedFields(), event.getOperationType());
					}
					finishedRevision(revision);
				}
			} break;
			case DELETE: {
				// TODO: Is this safe? Does this open up a timing hole where updates could be dropped?
				LOGGER.debug("Document containing revision field has been deleted; assuming revision=0");
				finishedRevision(REVISION_ZERO);
			} break;
			default: {
				throw new UnprocessableEventException("Cannot process event", event.getOperationType());
			}
		}
	}

	//
	// MongoDB helpers
	//

	private BsonDocument documentFilter() {
		return new BsonDocument("_id", DOCUMENT_ID);
	}

	@Override
	public BsonDocument rootDocumentsFilter() {
		return DOCUMENT_FILTER;
	}

	private <T> BsonDocument standardPreconditions(Reference<T> target) {
		BsonDocument filter = documentFilter();
		if (!target.path().isEmpty()) {
			String enclosingObjectKey = dottedFieldNameOf(target.enclosingReference(Object.class), rootRef);
			BsonDocument condition = new BsonDocument("$type", new BsonString("object"));
			filter.put(enclosingObjectKey, condition);
			LOGGER.debug("| Precondition: {} {}", enclosingObjectKey, condition);
		}
		return filter;
	}

	private <T> BsonDocument explicitPreconditions(Reference<T> target, Reference<Identifier> preconditionRef, Identifier requiredValue) {
		BsonDocument filter = standardPreconditions(target);
		BsonDocument precondition = new BsonDocument("$eq", new BsonString(requiredValue.toString()));
		filter.put(dottedFieldNameOf(preconditionRef, rootRef), precondition);
		return filter;
	}

	private <T> BsonDocument replacementDoc(Reference<T> target, T newValue) {
		return super.replacementDoc(target, formatter.object2bsonValue(newValue, target.targetType()), rootRef);
	}

	/**
	 * @return true if something changed
	 */
	private boolean doUpdate(BsonDocument updateDoc, BsonDocument filter) {
		LOGGER.debug("| Update: {}", updateDoc);
		LOGGER.debug("| Filter: {}", filter);
		UpdateResult result = collection.updateOne(filter, updateDoc);
		LOGGER.debug("| Update result: {}", result);
		if (result.wasAcknowledged()) {
			// NOTE: This case can occur in a few situations:
			// 1. A conditional update whose precondition failed
			// 2. An update inside a nonexistent node
			// 3. The bosk document has disappeared
			//
			// Differentiating these cases without transactions is complex,
			// and the only benefit of the Sequoia format is its simplicity,
			// so for now, we're opting not to handle this case.
			//
			// This means valid updates can be silently ignored during the window
			// between when a refurbish operation deletes the bosk document and
			// when the corresponding change event arrives. We are going to accept
			// and document this risk for the time being, unless we can determine
			// a sufficiently straightforward way to detect this situation.
			//
			// Therefore, when refurbishing from Sequoia to another format,
			// the system should be quiescent or else updates may be lost.

			assert result.getMatchedCount() <= 1;
			return result.getMatchedCount() >= 1;
		} else {
			LOGGER.error("MongoDB write was not acknowledged");
			LOGGER.trace("Details of MongoDB write not acknowledged:\n\tFilter: {}\n\tUpdate: {}\n\tResult: {}", filter, updateDoc, result);
			throw new IllegalStateException("Mongo write was not acknowledged: " + result);
		}
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitReplacement submitReplacement}</code>
	 * for each updated field.
	 */
	private void replaceUpdatedFields(@Nullable BsonDocument updatedFields) {
		if (updatedFields == null) {
			LOGGER.trace("| (No updated fields; nothing to replace)");
		} else {
			for (Map.Entry<String, BsonValue> entry : updatedFields.entrySet()) {
				String dottedName = entry.getKey();
				if (dottedName.startsWith(DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = referenceTo(dottedName, rootRef);
					} catch (InvalidTypeException e) {
						logNonexistentField(dottedName, e);
						continue;
					}
					LOGGER.debug("| Replace {}", ref);
					Object replacement = formatter.bsonValue2object(entry.getValue(), ref);
					downstream.submitReplacement(ref, replacement);
				} else {
					LOGGER.trace("| (Ignoring field: {})", dottedName);
				}
			}
		}
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitDeletion submitDeletion}</code>
	 * for each removed field.
	 */
	private void deleteRemovedFields(@Nullable List<String> removedFields, OperationType operationType) throws UnprocessableEventException {
		if (removedFields == null) {
			LOGGER.trace("| (No removed fields; nothing to delete)");
		} else {
			for (String dottedName : removedFields) {
				if (dottedName.startsWith(DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = referenceTo(dottedName, rootRef);
					} catch (InvalidTypeException e) {
						logNonexistentField(dottedName, e);
						continue;
					}
					LOGGER.debug("| Delete {}", ref);
					downstream.submitDeletion(ref);
				} else {
					throw new UnprocessableEventException("Deletion of metadata field " + dottedName, operationType);
				}
			}
		}
	}

	@Override
	public String toString() {
		return description;
	}

	private static final BsonDocument DOCUMENT_FILTER = new BsonDocument("_id", DOCUMENT_ID);
	private static final Logger LOGGER = LoggerFactory.getLogger(SequoiaFormatDriver.class);
}
