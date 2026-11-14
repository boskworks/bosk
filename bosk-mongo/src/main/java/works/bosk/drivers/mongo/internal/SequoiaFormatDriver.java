package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mongodb.client.result.UpdateResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonString;
import org.bson.BsonValue;
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
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;

import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import static java.util.Objects.requireNonNull;
import static works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static works.bosk.drivers.mongo.internal.AtomicUpdateOperation.Outcome.DONE;
import static works.bosk.drivers.mongo.internal.AtomicUpdateOperation.Outcome.NOTHING_CHANGED;
import static works.bosk.drivers.mongo.internal.BsonFormatter.referenceTo;

/**
 * Implements the {@link MongoDriverSettings.DatabaseFormat#SEQUOIA Sequoia} format.
 */
final class SequoiaFormatDriver<R extends StateTreeNode> extends AbstractFormatDriver<R> {
	private final String description;

	static final BsonString DOCUMENT_ID = new BsonString("boskDocument");

	SequoiaFormatDriver(
		BoskInfo<R> boskInfo,
		SessionCollection collection,
		MongoDriverSettings driverSettings,
		BsonSerializer bsonSerializer,
		Duration flushTimeout,
		BoskDriver downstream,
		Identifier formatId
	) {
		super(
			boskInfo.rootReference(),
			boskInfo.context(),
			new Formatter(boskInfo, bsonSerializer),
			collection,
			downstream,
			flushTimeout,
			formatId
		);
		this.description = getClass().getSimpleName() + ": " + driverSettings;
	}

	@Override
	public DatabaseFormat format() {
		return SEQUOIA;
	}

	@Override
	public <T> UpdateOperation submitReplacement(Reference<T> target, T newValue) {
		return atomic(() -> replaceInDocument(target, formatter.object2bsonValue(newValue, target.targetType()), rootRef, null, null));
	}

	@Override
	public <T> UpdateOperation submitConditionalCreation(Reference<T> target, T newValue) {
		return atomic(() -> {
			boolean changed = createInDocument(target, formatter.object2bsonValue(newValue, target.targetType()), rootRef);
			if (changed) {
				LOGGER.debug("| Object initialized");
			} else {
				LOGGER.debug("| No update");
			}
			return changed;
		});
	}

	@Override
	public <T> UpdateOperation submitDeletion(Reference<T> target) {
		return atomic(() -> deleteInDocument(target, rootRef, null, null));
	}

	@Override
	public <T> UpdateOperation submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		return atomic(() -> replaceInDocument(target, formatter.object2bsonValue(newValue, target.targetType()), rootRef, precondition, requiredValue));
	}

	@Override
	public <T> UpdateOperation submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		return atomic(() -> deleteInDocument(target, rootRef, precondition, requiredValue));
	}

	private static AtomicUpdateOperation atomic(BooleanSupplier operation) {
		return _ -> operation.getAsBoolean()? DONE: NOTHING_CHANGED;
	}

	@Override
	BsonStateAndMetadata readBsonStateAndMetadata() throws InvalidCollectionContentsException {
		try (DocCursor cursor = collection
			.find(documentFilter(rootRef))
			.limit(1)
			.cursor()
		) {
			BsonDocument document = cursor.next();
			validateFormatId(document);
			BsonString id = document.getString("_id");
			RevisionTuple revision = RevisionTuple.from(document);
			return new BsonStateAndMetadata(
				document.getDocument(DocumentFields.state.name()),
				formatter.epochOf(document),
				Map.of(id, revision)
			);
		} catch (NoSuchElementException e) {
			throw new InvalidCollectionContentsException(SEQUOIA, "State document not found: " + DOCUMENT_ID, e);
		} catch (BsonInvalidOperationException e) {
			throw new InvalidCollectionContentsException(SEQUOIA, "State document is missing required fields: " + DOCUMENT_ID, e);
		}

	}

	@Override
	public void writeAllState(StateAndMetadata<R> priorContents) {
		BsonString epoch = priorContents.epoch().orElseGet(() -> new BsonString(UUID.randomUUID().toString()));
		replaceFlushLock(Optional.of(epoch), priorContents.documentRevisions());

		// Sequoia has only one document
		BsonValue initialState = formatter.object2bsonValue(priorContents.state(), rootRef.targetType());
		RevisionTuple newRevision = RevisionTuple.nextAfter(
			priorContents.documentRevisions().get(DOCUMENT_ID));

		// Note: the manifest is written by MainDriver, so the caller must have started
		// a transaction that makes this state document write and the manifest write atomic.
		BsonDocument update = new BsonDocument("$set", initialDocument(initialState, epoch, newRevision, DOCUMENT_ID));
		BsonDocument filter = documentFilter(rootRef);
		UpdateOptions options = new UpdateOptions().upsert(true);
		LOGGER.debug("** Initial upsert for {}", DOCUMENT_ID);
		LOGGER.trace("| Filter: {}", filter);
		LOGGER.trace("| Update: {}", update);
		LOGGER.trace("| Options: {}", options);
		UpdateResult result = collection.updateOne(filter, update, options);
		LOGGER.debug("| Result: {}", result);

		// Update the state that we "know about"
		finishedRevision(DOCUMENT_ID, newRevision);
	}

	/**
	 * We're required to cope with anything we might ourselves do during initialization
	 * (writing the state document in {@link #writeAllState}, and the manifest that
	 * {@link MainDriver} writes).
	 */
	@Override
	public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException, FormatChangedException {
		fieldTracker.processEvent(event);
		assert event.getDocumentKey() != null;
		if (isManifestID(event.getDocumentKey().get("_id"))) {
			/* We're required to cope with anything we might ourselves do during initialization,
			 * but outside that, we want to be as strict as we can
			 * so incompatible database changes don't go unnoticed.
			 */
			validateManifestEvent(event, Manifest.forSequoia(formatId));
			return;
		}
		if (!DOCUMENT_FILTER.equals(event.getDocumentKey())) {
			LOGGER.debug("Ignoring event for unrecognized document key: {}", event.getDocumentKey());
			return;
		}
		validateEventFormatId(event);
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
				BsonString docId = documentIdOf(event);
				MapValue<String> diagnosticAttributes = diagnosticAttributesFor(docId);
				try (
					var _ = context.withOnly(diagnosticAttributes)
				) {
					RevisionTuple revision = RevisionTuple.from(fullDocument);
					Optional<BsonString> epoch = formatter.epochOf(fullDocument);
					BsonDocument state = fullDocument.getDocument(DocumentFields.state.name(), null);
					if (state == null) {
						throw new UnprocessableEventException("Missing state field", event.getOperationType());
					}
					R newRoot = formatter.document2object(state, rootRef);
					// Note that we do not check the revision here. We probably should... but this actually
					// saves us in MongoDriverResiliencyTest.documentReappears_recovers because when the doc
					// disappears, we don't null out the applied revisions. TODO: Rethink what's the right way to handle this.
					LOGGER.debug("| Replace {}", rootRef);
					downstream.submitReplacement(rootRef, newRoot);
					if (!flushLock.get().epochMatches(epoch)) {
						// Adopt the new epoch so future flushes don't needlessly reconnect
						replaceFlushLock(epoch, Map.of());
					}
					finishedRevision(docId, revision);
				}
			} break;
			case UPDATE: {
				UpdateDescription updateDescription = event.getUpdateDescription();
				if (updateDescription != null) {
					BsonString updateDocId = documentIdOf(event);
					BsonDocument updatedFields = updateDescription.getUpdatedFields();
					BsonInt64 revisionValue = updatedFields.getInt64(DocumentFields.revision.name(), null);
					if (revisionValue == null) {
						throw new UnprocessableEventException("Sequoia update does not carry a revision", event.getOperationType());
					}
					BsonString incarnationValue = updatedFields.getString(DocumentFields.incarnation.name(), null);
					RevisionTuple revision = new RevisionTuple(
						incarnationValue == null
							? requireNonNull(flushLock.get().currentIncarnation(updateDocId), "Current incarnation")
							: incarnationValue.getValue(),
						revisionValue.longValue());

					MapValue<String> diagnosticAttributes = diagnosticAttributesFor(updateDocId);
					try (
						var _ = context.withOnly(diagnosticAttributes)
					) {
						if (shouldSkip(flushLock.get().epoch(), updateDocId, revision)) {
							LOGGER.debug("Skipping revision {} for {}", revision, updateDocId);
							return;
						}

						replaceUpdatedFields(updateDescription.getUpdatedFields());
						deleteRemovedFields(updateDescription.getRemovedFields(), event.getOperationType());
					}
					finishedRevision(updateDocId, revision);
				}
			} break;
			case DELETE: {
				// The revision number and incarnation have been deleted along with
				// the document. We deliberately don't clear the applied revisions:
				// the re-created document's revision and the epoch mechanism together
				// ensure that flushes still wait for the re-initialized collection.
				LOGGER.debug("Document containing the revision has been deleted");
			} break;
			default: {
				throw new UnprocessableEventException("Cannot process event", event.getOperationType());
			}
		}
	}

	//
	// MongoDB helpers
	//

	@Override
	protected BsonDocument documentFilter(Reference<?> documentRef) {
		return new BsonDocument("_id", DOCUMENT_ID);
	}

	@Override
	public BsonDocument rootDocumentsFilter() {
		return DOCUMENT_FILTER;
	}

	@Override
	protected FlushRevisions readRevisionsToFlush() throws FlushFailureException, InterruptedException {
		LOGGER.debug("readRevisionsToFlush");
		try (DocCursor cursor = collection
			.findLatest(documentFilter(rootRef))
			.limit(1)
			.projection(fields(include(
				"_id",
				DocumentFields.epoch.name(),
				DocumentFields.incarnation.name(),
				DocumentFields.revision.name())))
			.cursor()
		) {
			if (!cursor.hasNext()) {
				throw new RevisionFieldDisruptedException("No state document found");
			}
			BsonDocument document = cursor.next();
			BsonString id = document.getString("_id");
			return new FlushRevisions(
				formatter.epochOf(document),
				List.of(new FlushLock.Target(id, RevisionTuple.from(document))));
		} catch (MongoInterruptedException e) {
			Thread.interrupted();
			InterruptedException exception = new InterruptedException("Interrupted while reading revisions");
			exception.initCause(e);
			throw exception;
		} catch (MongoException e) {
			throw new FlushFailureException("Failed to read revisions", e);
		}
	}

	/**
	 * @return true if something changed
	 */
	@Override
	protected boolean doUpdate(BsonDocument updateDoc, BsonDocument rawFilter) {
		BsonDocument filter = withFormatId(rawFilter);
		LOGGER.debug("| Update: {}", updateDoc);
		LOGGER.debug("| Filter: {}", filter);
		UpdateResult result = collection.updateOne(filter, updateDoc);
		LOGGER.debug("| Update result: {}", result);
		// NOTE: A no-match is normal here: a conditional update whose precondition
		// failed, or an update inside a node that doesn't exist, is a no-op.
		//
		// It can also mean the bosk document has disappeared. If it was replaced
		// by a refurbish to another format, the update is not a no-op at all: it
		// was planned against the old format and would be lost unless it is
		// retried against the new one.
		//
		// Sequoia can't tell these apart from the update result, so UpdateDriver
		// asks the format driver to check the manifest whenever an update matches
		// nothing; that check turns the refurbish case into a retry against the
		// new format instead of a lost update.

		requireAcknowledged(result, filter, updateDoc, "Update");
		assert result.getMatchedCount() <= 1;
		return result.getMatchedCount() >= 1;
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
