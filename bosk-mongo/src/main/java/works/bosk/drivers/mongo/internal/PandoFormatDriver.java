package works.bosk.drivers.mongo.internal;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskConfig.TenancyModel;
import works.bosk.BoskConfig.TenancyModel.Fixed;
import works.bosk.BoskConfig.TenancyModel.Persistent;
import works.bosk.BoskContext.Tenant;
import works.bosk.BoskContext.Tenant.Established;
import works.bosk.BoskContext.Tenant.None;
import works.bosk.BoskContext.Tenant.TenantId;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.Entity;
import works.bosk.EnumerableByIdentifier;
import works.bosk.Identifier;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.RootReference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.MongoDriverSettings.OrphanDocumentMode;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.drivers.mongo.exceptions.FormatMisconfigurationException;
import works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NoSuchTenantException;
import works.bosk.exceptions.NotYetImplementedException;
import works.bosk.util.PerTenant;
import works.bosk.util.PerTenant.MultiTenant;
import works.bosk.util.PerTenant.NoTenant;

import static com.mongodb.ReadConcern.LOCAL;
import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static org.bson.BsonBoolean.TRUE;
import static works.bosk.Path.parseParameterized;
import static works.bosk.drivers.mongo.internal.BsonFormatter.docBsonPath;
import static works.bosk.drivers.mongo.internal.BsonSurgeon.BSON_PATH_FIELD;
import static works.bosk.drivers.mongo.internal.Formatter.REVISION_ZERO;
import static works.bosk.drivers.mongo.internal.Formatter.getTenantFromDocumentId;
import static works.bosk.util.Classes.enumerableByIdentifier;
import static works.bosk.util.PerTenant.MultiTenant.multiTenant;

/**
 * Implements {@link PandoFormat}.
 */
final class PandoFormatDriver<R extends StateTreeNode> extends AbstractFormatDriver<R> {
	private final String description;
	private final PandoFormat format;
	private final MongoDriverSettings settings;
	private final BsonSurgeon bsonSurgeon;
	private final Demultiplexer demultiplexer = new Demultiplexer();
	private final List<Reference<? extends EnumerableByIdentifier<?>>> graftPoints;

	private static final BsonString ROOT_PATH = new BsonString("/");

	PandoFormatDriver(
		BoskInfo<R> boskInfo,
		TransactionalCollection collection,
		MongoDriverSettings driverSettings,
		PandoFormat format,
		BsonSerializer bsonSerializer,
		long flushTimeoutMS,
		BoskDriver downstream
	) {
		super(
			boskInfo.rootReference(),
			boskInfo.context(),
			boskInfo.tenancyModel(),
			new Formatter(boskInfo, bsonSerializer),
			collection,
			downstream,
			flushTimeoutMS,
			() -> boskInfo.bosk().entireState()
		);
		this.description = getClass().getSimpleName() + ": " + driverSettings;
		this.settings = driverSettings;
		this.format = format;
		this.graftPoints = format.graftPoints().stream()
			.map(s -> referenceTo(s, rootRef))
			.sorted(comparing((Reference<?> ref) -> ref.path().length()).reversed())
			.collect(toList());
		this.bsonSurgeon = new BsonSurgeon(graftPoints);
	}

	private static Reference<EnumerableByIdentifier<Entity>> referenceTo(String pathString, RootReference<?> rootRef) {
		try {
			return rootRef.then(enumerableByIdentifier(Entity.class), parseParameterized(pathString));
		} catch (InvalidTypeException e) {
			throw new FormatMisconfigurationException("Path does not point to a Catalog or SideTable: " + pathString, e);
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		collection.ensureTransactionStarted();
		try {
			doReplacement(target, newValue);
		} catch (NoSuchTenantException e) {
			initializeTenant(context.getTenantId(), formatter.object2bsonValue(newValue, target.targetType()), REVISION_ZERO);
		}
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		collection.ensureTransactionStarted();
		Reference<?> mainRef = mainRef(target);
		BsonDocument filter = documentFilter(mainRef)
			.append(BsonFormatter.dottedFieldNameOf(target, mainRef), new BsonDocument("$exists", TRUE));
		if (documentExists(filter)) {
			LOGGER.debug("Already exists: {}", filter);
			collection.abortTransaction();
			return;
		}
		doReplacement(target, newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		doDelete(target);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		collection.ensureTransactionStarted();
		if (preconditionFailed(precondition, requiredValue)) {
			collection.abortTransaction();
			return;
		}
		doReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		collection.ensureTransactionStarted();
		if (preconditionFailed(precondition, requiredValue)) {
			collection.abortTransaction();
			return;
		}
		doDelete(target);
	}

	@Override
	PerTenant<BsonStateAndMetadata> loadBsonStateAndMetadata() throws InvalidCollectionContentsException {
		// Look for runs of state documents with the same tenant info.
		// Make a BsonStateAndMetadata for each run.
		SortedMap<Established, BsonStateAndMetadata> states = new TreeMap<>(comparing(t -> switch (t) {
			case None _ -> "";
			case TenantId(var id) -> id.toString();
		}));
		List<BsonDocument> partsBuffer = new ArrayList<>();
		try (MongoCursor<BsonDocument> cursor = collection
			.withReadConcern(LOCAL) // The revision field needs to be the latest
			.find(regex("_id", "^[|<]"))
			.sort(new BsonDocument("_id", new BsonInt32(-1))) // Root doc last
			.cursor()
		) {
			while (cursor.hasNext()) {
				BsonDocument lastPart = cursor.next();
				partsBuffer.add(lastPart);
				// Only the root/main document has a path field, and it's always "/" regardless
				// of tenancy mode; sub-part documents have no path field at all.
				if (ROOT_PATH.equals(lastPart.getString(DocumentFields.path.name(), null))) {
					// The lastPart is a main part

					// Pull what we need from the parts before gather() mutates them
					BsonInt64 revision = lastPart.getInt64(DocumentFields.revision.name(), null);
					BsonDocument diagnosticAttributes = Formatter.getDiagnosticAttributesIfAny(lastPart);
					Established documentTenant = getTenantFromDocumentId(lastPart.getString("_id"));

					BsonDocument state = gather(partsBuffer); // mutates partsBuffer!

					// Note that documentTenant is strictly the tenant from the document.
					// In the case of TenancyFormat.NONE and TenancyModel.Fixed, this
					// will be Tenant.NONE which is not actually the correct fixed tenant ID,
					// but that's ok because we're not going to use it as the tenant ID anyway.
					// This is just a way of describing what we've found in the database.
					states.put(documentTenant, new BsonStateAndMetadata(
						state,
						revision,
						diagnosticAttributes
					));

					partsBuffer.clear();
				}
			}
		}

		if (!partsBuffer.isEmpty()) {
			throw new IllegalStateException("Found parts without a main document: "
				+ partsBuffer.stream().map(part -> part.get("_id")).toList());
		}

		// Note: we tolerate extra documents here, in the spirit of HASTY mode:
		// documents are not necessarily deleted promptly, so there can be extra ones lying around.
		return switch (format.tenancyFormat()) {
			case NONE -> {
				var theState = states.get(Tenant.NONE);
				if (theState == null) {
					// Appropriate error message depends on what we did actually discover
					if (states.isEmpty()) {
						throw new IllegalStateException("No state documents");
					} else {
						throw new IllegalStateException("All state documents have tenant info, inconsistent with tenancyFormat=NONE");
					}
				}
				yield switch (tenancyModel) {
					case TenancyModel.None _ -> new NoTenant<>(theState);
					case Fixed(var id) -> MultiTenant.singleton(Tenant.setTo(id), theState);
					case Persistent _ -> throw new AssertionError(
						"Persistent tenancy should use ID_PREFIX format");
				};
			}
			case ID_PREFIX -> states.entrySet().stream()
				.filter(e -> e.getKey() != Tenant.NONE)
				.collect(multiTenant(e -> (TenantId) e.getKey(), Entry::getValue));
		};
	}

	@Override
	@NonNull PerTenant<BsonInt64> readRevisionNumbers() throws RevisionFieldDisruptedException {
		LOGGER.debug("readRevisionNumbers");
		try {
			return switch (format.tenancyFormat()) {
				case NONE -> {
					try (MongoCursor<BsonDocument> cursor = revisionDocumentCursor()) {
						while (cursor.hasNext()) {
							BsonDocument doc = cursor.next();
							if (getTenantFromDocumentId(doc.getString("_id")) instanceof None) {
								BsonInt64 revision = doc.getInt64(DocumentFields.revision.name(), REVISION_ZERO);
								yield switch (tenancyModel) {
									case TenancyModel.None _ -> NoTenant.just(revision);
									case Fixed(var id) -> MultiTenant.singleton(Tenant.setTo(id), revision);
									case Persistent _ -> throw new AssertionError(
										"Persistent tenancy should use ID_PREFIX format");
								};
							}
						}
					}
					throw new RevisionFieldDisruptedException("No matching root document");
				}
				case ID_PREFIX -> {
					SortedMap<TenantId, BsonInt64> revisions = new TreeMap<>();
					try (MongoCursor<BsonDocument> cursor = revisionDocumentCursor()) {
						while (cursor.hasNext()) {
							BsonDocument doc = cursor.next();
							if (getTenantFromDocumentId(doc.getString("_id")) instanceof TenantId tid) {
								revisions.put(tid, doc.getInt64(DocumentFields.revision.name(), REVISION_ZERO));
							}
						}
					}
					yield new MultiTenant<>(revisions);
				}
			};
		} catch (RuntimeException e) {
			throw new RevisionFieldDisruptedException(e);
		}
	}

	/**
	 * For efficiency, this modifies <code>partsList</code> in-place.
	 * <p>
	 * A version of {@link BsonSurgeon#gather(List)} that handles tenant IDs.
	 * The "part" recipe documents must all have the same tenant ID.
	 */
	private BsonDocument gather(List<BsonDocument> allParts) {
		var tenantId = getTenantFromDocumentId(allParts.getFirst().getString("_id"));
		allParts.forEach(d -> removeTenantFromId(d, tenantId));
		return bsonSurgeon.gather(allParts);
	}

	/**
	 * A version of {@link BsonSurgeon#scatter(Reference, BsonDocument)} that handles tenant IDs.
	 * The resulting "part" recipe documents have the tenant ID prepended to their IDs.
	 * <p>
	 * (The incoming {@code value} is not a fully fledged database document yet:
	 * it is the document produced by the {@link BsonSerializer}, not a "recipe" from the BsonSurgeon.)
	 */
	private <T> List<BsonDocument> scatter(Reference<T> target, BsonDocument value, String idPrefix) {
		return bsonSurgeon.scatter(target, value).stream()
			.peek(d -> prependTenantToId(d, idPrefix))
			.toList();
	}

	private void removeTenantFromId(BsonDocument doc, Established tenant) {
		String id = doc.getString(BSON_PATH_FIELD).getValue();
		assert id.startsWith(tenantPrefix(tenant)):
			"Document ID must start with [" + tenantPrefix(tenant) + "]: " + id;
		doc.put(BSON_PATH_FIELD, new BsonString(removeTenantFrom(id)));
	}

	private String removeTenantFrom(String documentId) {
		return documentId.substring(documentId.indexOf("|"));
	}

	private void prependTenantToId(BsonDocument doc, String idPrefix) {
		doc.put(BSON_PATH_FIELD, new BsonString(idPrefix + doc.getString(BSON_PATH_FIELD).getValue()));
	}

	@Override
	public void initializeCollection(PerTenant<StateAndMetadata<R>> priorContentsArg) {
		var allPriorContents = normalizePerTenant(validateAndNormalize(priorContentsArg));
		ensureFlushLocksInitialized(allPriorContents);
		allPriorContents.forEach((tenant, priorContents) -> {
			BsonValue initialState = formatter.object2bsonValue(priorContents.state(), rootRef.targetType());
			BsonInt64 priorRevision = priorContents.revision();

			LOGGER.debug("** Initial upsert");
			initializeTenant(tenant, initialState, priorRevision);
		});
		writeManifest(Manifest.forPando(format));
	}

	private void initializeTenant(Established tenant, BsonValue initialState, BsonInt64 priorRevision) {
		// Note that priorContents.diagnosticAttributes are ignored, and we use the attributes from this thread
		collection.ensureTransactionStarted();
		String tenantPrefix = tenantPrefix(tenant);
		if (initialState instanceof BsonDocument) {
			upsertAndRemoveSubParts(rootRef, initialState.asDocument(), tenantPrefix); // Mutates initialState!
		}
		BsonString documentId = new BsonString(tenantPrefix + "|");
		BsonInt64 newRevision = new BsonInt64(1 + priorRevision.longValue());
		BsonDocument update = new BsonDocument("$set", initialDocument(initialState, newRevision, documentId));
		BsonDocument filter = rootDocumentsFilter();
		filter.put("_id", documentId);
		UpdateOptions options = new UpdateOptions().upsert(true);
		LOGGER.trace("| Filter: {}", filter);
		LOGGER.trace("| Update: {}", update);
		LOGGER.trace("| Options: {}", options);
		UpdateResult result = collection.updateOne(filter, update, options);
		LOGGER.debug("| Result: {}", result);

		// Update the state that we "know about"
		finishedRevision(tenant, newRevision);
	}

	/**
	 * @return {@code given} if it's value, except for {@link Fixed},
	 * where it's coerced into either a {@link NoTenant} or a {@link MultiTenant} with one tenant
	 * depending on the {@link PandoFormat#tenancyFormat() tenancyFormat}.
	 */
	private PerTenant<StateAndMetadata<R>> validateAndNormalize(PerTenant<StateAndMetadata<R>> given) {
		return switch (tenancyModel) {
			case TenancyModel.None _ -> switch (given) {
				case NoTenant<StateAndMetadata<R>> v -> v;
				case MultiTenant<StateAndMetadata<R>> _ -> throw new IllegalArgumentException(
					"Tenancy model " + tenancyModel + " not compatible with MultiTenant state");
			};
			case Persistent _ -> switch (given) {
				case MultiTenant<StateAndMetadata<R>> v -> v;
				case NoTenant<StateAndMetadata<R>> _ -> throw new IllegalArgumentException(
					"Tenancy model " + tenancyModel + " not compatible with NoTenant state");
			};
			case Fixed(Identifier id) -> switch (given) { // The complex, permissive one
				case NoTenant<StateAndMetadata<R>> v -> switch (format.tenancyFormat()) {
					case NONE -> v;
					case ID_PREFIX -> MultiTenant.singleton(Tenant.setTo(id), v.value());
				};
				case MultiTenant<StateAndMetadata<R>> v -> switch (format.tenancyFormat()) {
					case NONE -> v.asNoTenant(Tenant.setTo(id));
					case ID_PREFIX -> v;
				};
			};
		};
	}

	private String tenantPrefix(Established tenant) {
		return switch (format.tenancyFormat()) {
			case NONE -> "";
			case ID_PREFIX -> switch (tenant) {
				case TenantId(var id) -> "<" + id + ">";
				default -> throw new IllegalStateException("Expected tenant ID for ID_PREFIX tenancy format, but got: " + tenant);
			};
		};
	}

	/**
	 * We're required to cope with anything we might ourselves do in {@link FormatDriver#initializeCollection}.
	 */
	@Override
	public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		assert event.getDocumentKey() != null;
		BsonValue bsonDocumentID = event.getDocumentKey().get("_id");
		if (isManifestID(bsonDocumentID)) {
			/* We're required to cope with anything we might ourselves do in {@link #initializeCollection},
			 * but outside that, we want to be as strict as we can
			 * so incompatible database changes don't go unnoticed.
			 */
			validateManifestEvent(event, Manifest.forPando(format));
			return;
		}
		if (!(bsonDocumentID instanceof BsonString s) || !(s.getValue().contains("|"))) {
			LOGGER.debug("Ignoring event for unrecognized document key: {} type {}", event.getDocumentKey(), bsonDocumentID.getClass());
			return;
		}

		// This is an event we care about

		if (event.getTxnNumber() == null) {
			LOGGER.debug("Processing standalone event {} on {}", event.getOperationType(), event.getDocumentKey());
			processTree(singletonList(event));
		} else {
			demultiplexer.add(event);
			if (isFinalEventOfTree(event)) {
				LOGGER.debug("Processing final event {} on {}", event.getOperationType(), event.getDocumentKey());
				processTree(demultiplexer.pop(event));
			} else {
				LOGGER.debug("Queueing event {} on {}", event.getOperationType(), event.getDocumentKey());
			}
		}
	}

	/**
	 * The final event updates the revision field of the root document.
	 */
	private boolean isFinalEventOfTree(ChangeStreamDocument<BsonDocument> event) {
		// Only the root document has a bson path ending with "|" because every other document
		// has a bson path ending with the last path segment.
		return
			event.getDocumentKey().get("_id").asString().getValue().endsWith("|")
				&& updateEventHasField(event, DocumentFields.revision);
	}

	private void processTree(List<ChangeStreamDocument<BsonDocument>> events) throws UnprocessableEventException {
		ChangeStreamDocument<BsonDocument> finalEvent = events.getLast();
		Established tenant = tenantFor(finalEvent.getDocumentKey().getString("_id"));
		switch (finalEvent.getOperationType()) {
			case INSERT: case REPLACE: {
				BsonDocument fullDocument = finalEvent.getFullDocument();
				if (fullDocument == null) {
					throw new UnprocessableEventException("Missing fullDocument on final event", finalEvent.getOperationType());
				}

				// Grab the tenant and diagnostics early. If we're supposed to skip this event,
				// we still need to stash the tenant for later events.
				MapValue<String> diagnosticAttributes = formatter.eventDiagnosticAttributesFromFullDocument(fullDocument);

				BsonInt64 revision = formatter.getRevisionFromFullDocument(fullDocument);
				if (shouldSkip(tenant, revision)) {
					LOGGER.debug("Skipping revision {}", revision.longValue());
					return;
				}

				try (
					var _ = context.withTenant(tenant);
					var _ = context.withOnly(diagnosticAttributes)
				) {
					BsonDocument state = fullDocument.getDocument(DocumentFields.state.name());
					if (state == null) {
						// Final event has only the new revision number; the previous event is the main event.
						// A standalone INSERT/REPLACE always has a state field; lacking one is nonsensical
						// and implies a programming error or an unexpected database state.
						assert events.size() >= 2 : "INSERT/REPLACE without state needs a prior main event";
						ChangeStreamDocument<BsonDocument> mainEvent = events.get(events.size() - 2);
						LOGGER.debug("Main event is {} on {}", mainEvent.getOperationType(), mainEvent.getDocumentKey());
						propagateDownstream(mainEvent, events.subList(0, events.size() - 2));
					} else {
						LOGGER.debug("Main event is final event");
						propagateDownstream(finalEvent, events.subList(0, events.size() - 1));
					}
				}

				finishedRevision(tenant, revision);
			} break;
			case UPDATE: {
				// TODO: Combine code with INSERT and REPLACE events
				BsonInt64 revision = formatter.getRevisionFromUpdateEvent(finalEvent);
				if (shouldSkip(tenant, revision)) {
					LOGGER.debug("Skipping revision {}", revision.longValue());
					return;
				}
				MapValue<String> attributes = formatter.eventDiagnosticAttributesFromUpdate(finalEvent);
				try (
					var _ = context.withTenant(tenant);
					var _ = context.withOnly(attributes)
				) {
					boolean mainEventIsFinalEvent = updateEventHasField(finalEvent, DocumentFields.state); // If the final update changes only the revision field, then it's not the main event
					if (mainEventIsFinalEvent) {
						LOGGER.debug("Main event is final event");
						propagateDownstream(finalEvent, events.subList(0, events.size() - 1));
					} else if (events.size() < 2) {
						LOGGER.debug("Main event is a no-op");
					} else {
						ChangeStreamDocument<BsonDocument> mainEvent = events.get(events.size() - 2);
						LOGGER.debug("Main event is {} on {}", mainEvent.getOperationType(), mainEvent.getDocumentKey());
						propagateDownstream(mainEvent, events.subList(0, events.size() - 2));
					}
				}
				finishedRevision(tenant, revision);
			} break;
			case DELETE: {
				// No other events in the transaction matter if the root document is gone
				LOGGER.debug("Document containing revision field has been deleted; assuming revision=0");
				finishedRevision(tenant, REVISION_ZERO);
			} break;
			default: {
				throw new UnprocessableEventException("Cannot process event", finalEvent.getOperationType());
			}
		}
	}

	private void propagateDownstream(ChangeStreamDocument<BsonDocument> mainEvent, List<ChangeStreamDocument<BsonDocument>> priorEvents) throws UnprocessableEventException {
		switch (mainEvent.getOperationType()) {
			case INSERT: case REPLACE: {
				BsonDocument fullDocument = mainEvent.getFullDocument();
				if (fullDocument == null) {
					throw new UnprocessableEventException("Missing fullDocument on main event", mainEvent.getOperationType());
				}

				BsonDocument state = fullDocument.getDocument(DocumentFields.state.name(), null);
				if (state == null) {
					throw new UnprocessableEventException("Missing state field", mainEvent.getOperationType());
				}

				Reference<?> mainRef;
				BsonDocument bsonState;
				if (priorEvents == null) {
					LOGGER.debug("No prior events");
					bsonState = state;
					mainRef = documentID2MainRef(mainEvent.getDocumentKey().getString("_id").getValue(), mainEvent);
				} else {
					LOGGER.debug("{} prior events", priorEvents.size());
					List<BsonDocument> parts = subpartDocuments(priorEvents);
					parts.add(fullDocument);
					String id = fullDocument.getString("_id").getValue();
					bsonState = gather(parts); // Mutates parts
					mainRef = documentID2MainRef(id, mainEvent);
				}

				LOGGER.debug("| Replace downstream {}", mainRef);
				submitReplacementDownstream(mainRef, bsonState);
			} break;
			case UPDATE: {
				Reference<?> mainRef = documentID2MainRef(mainEvent.getDocumentKey().getString("_id").getValue(), mainEvent);
				UpdateDescription updateDescription = mainEvent.getUpdateDescription();
				if (updateDescription != null) {
					replaceUpdatedFields(mainRef, updateDescription.getUpdatedFields(), subpartDocuments(priorEvents), mainEvent.getOperationType());
					deleteRemovedFields(mainRef, updateDescription.getRemovedFields(), mainEvent.getOperationType());
				}
			} break;
			case DELETE: {
				// No other events in the transaction matter if the main document is deleted
				Reference<?> mainRef = mainRef(documentID2MainRef(mainEvent.getDocumentKey().getString("_id").getValue(), mainEvent));
				LOGGER.debug("| Delete downstream {}", mainRef);
				downstream.submitDeletion(mainRef);
			} break;
			default: {
				throw new UnprocessableEventException("Cannot process event", mainEvent.getOperationType());
			}
		}
	}

	private List<BsonDocument> subpartDocuments(List<ChangeStreamDocument<BsonDocument>> priorEvents) {
		return priorEvents.stream()
			.filter(e -> OPERATIONS_TO_INCLUDE_IN_GATHER.contains(e.getOperationType()))
			.map(this::fullDocumentForSubPart)
			.collect(toCollection(ArrayList::new));
	}

	private @NonNull BsonDocument fullDocumentForSubPart(ChangeStreamDocument<BsonDocument> event) {
		BsonDocument result = event.getFullDocument();
		if (result == null) {
			throw new IllegalStateException("No full document in change stream event for subpart: " + event.getOperationType() + " on " + event.getDocumentKey());
		}
		return result;
	}

	/**
	 * This lets us use Java generics to avoid some ugly typecasts
	 */
	private <T> void submitReplacementDownstream(Reference<T> mainRef, BsonDocument bsonState) {
		T newValue = formatter.document2object(bsonState, mainRef);
		downstream.submitReplacement(mainRef, newValue);
	}

	private Reference<?> documentID2MainRef(String documentId, ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		// referenceTo does everything we need already. Build a fake dotted field name and use that
		String dottedName = "state" + removeTenantFrom(documentId)
			.replace('|', '.');
		try {
			return BsonFormatter.referenceTo(dottedName, rootRef);
		} catch (InvalidTypeException e) {
			throw new UnprocessableEventException("Invalid path from document ID: \"" + documentId + "\"", e, event.getOperationType());
		}
	}

	private static boolean updateEventHasField(ChangeStreamDocument<BsonDocument> event, DocumentFields field) {
		if (event == null) {
			return false;
		}

		BsonDocument updatedFields;
		switch (event.getOperationType()) {
			case UPDATE -> {
				UpdateDescription updateDescription = event.getUpdateDescription();
				if (updateDescription == null) {
					return false;
				}
				updatedFields = updateDescription.getUpdatedFields();

				// This catches the case of somebody deleting the field manually
				List<String> removedFields = updateDescription.getRemovedFields();
				if (removedFields != null) {
					if (removedFields.stream().anyMatch(k -> k.startsWith(field.name()))) {
						return true;
					}
				}
			}
			case INSERT -> {
				// A change that was submitted as an "update" but was actually an upsert
				// will transmute into an insert if the document didn't exist, so we need
				// to handle those too.
				updatedFields = event.getFullDocument();
			}
			default -> {
				return false;
			}
		}

		if (updatedFields == null) {
			return false;
		}

		return updatedFields.keySet().stream().anyMatch(k -> k.startsWith(field.name()));
	}

	//
	// MongoDB helpers
	//

	/**
	 * A note on <em>pre-delete</em> operations:
	 * <p>
	 * We are changing a target field located either in the root document or in a sub-document.
	 * We distinguish these cases based on whether the root document's update event contains
	 * the {@code state} field: if it does, then that's the main event;
	 * if it doesn't, then that event is simply transmitting the new revision number,
	 * and the previous event is the main event.
	 * <p>
	 * This algorithm falls down in the following case:
	 * if, say, an entire {@code Catalog} in the root document is replaced by one that has
	 * the same domain and the same number of entries with the same IDs,
	 * then the root document's update should be considered the main event,
	 * yet because the document's contents are actually unchanged,
	 * MongoDB will suppress that event, and we're left with no main event!
	 * <p>
	 * To prevent this suppression, we delete the target fields before setting them.
	 * In this way, the update operation is never a no-op even if the document ends up unchanged.
	 * This seems slightly sensitive to the exact behaviour of MongoDB transactions,
	 * but if this stops working, there are alternatives we could consider.
	 * For example, the revision field could become an object that contains the path
	 * to the updated field, like <code>{ "12345": "/path/to/target" }</code>.
	 * Since the revision field always changes on every update, no amount of cleverness
	 * could end up suppressing this update.
	 * <p>
	 * <em>Implementation note</em>: While {@link SequoiaFormatDriver} has calls like <code>doUpdate</code>,
	 * Pando uses <code>doReplacement</code> and <code>doDelete</code>.
	 * The reason is that Sequoia, being a single-document format, can implement all its preconditions
	 * (be they conditional updates, or existence of containing objects, etc.) as query filters,
	 * and so can do every update (replacements and deletions) as a single document update operation.
	 * In contrast, Pando implements its preconditions using read operations and actual if statements in the code,
	 * and so the actual replacement/deletion is unconditional, because it won't be executed if the precondition fails.
	 * <p>
	 * TODO: We could organize the Sequoia code in a similar manner if doReplacement and doDelete
	 *  accepted filter arguments (which Pando simply wouldn't use).
	 *  It's complicated by the fact that the zero-match case in Sequoia is highly ambiguous.
	 */
	private <T> void doReplacement(Reference<T> target, T newValue) {
		collection.ensureTransactionStarted();
		LOGGER.debug("doReplacement({})", target);
		Reference<?> mainRef = mainRef(target);
		BsonValue value = formatter.object2bsonValue(newValue, target.targetType());
		if (value instanceof BsonDocument b) {
			deletePartsUnder(target);
			upsertAndRemoveSubParts(target, b, tenantPrefix(context.getEstablishedTenant()));
			// Note that value will now have the sub-parts removed
		}
		if (rootRef.equals(mainRef)) {
			LOGGER.debug("| Root ref is main ref");
			LOGGER.debug("| Pre-delete on root document");
			String key = BsonFormatter.dottedFieldNameOf(target, rootRef);
			LOGGER.debug("| Pre-delete field {}", key);
			doUpdate( // Important: don't bump the revision field because that's how we identify the last event in a transaction
				new BsonDocument("$unset", new BsonDocument(key, BsonNull.VALUE)),
				standardRootPreconditions(target));
			LOGGER.debug("| Update root document");
			doUpdate(replacementDoc(target, value, rootRef), standardRootPreconditions(target));
		} else {
			// Note: don't use mainPart's ID. TODO: Is this ok? Why is the ID wrong?
			BsonDocument filter = documentFilter(mainRef);
			if (target.equals(mainRef)) {
				// Upsert the main doc
				// TODO: merge this with the same code in upsertAndRemoveSubParts
				LOGGER.debug("| Pre-delete main document");
				collection.deleteOne(filter);

				LOGGER.debug("| Update main document");
				BsonDocument update = new BsonDocument("$set",
					filter.clone()
						.append(DocumentFields.state.name(), value));
				LOGGER.debug("| Update: {}", update);
				LOGGER.debug("| Filter: {}", filter);
				collection.updateOne(filter, update, new UpdateOptions().upsert(true));

				// Move up to the parent document to set the "true" stub
				mainRef = mainRef(mainRef.enclosingReference(Object.class));
				filter = documentFilter(mainRef);
				value = TRUE;
				LOGGER.debug("| Move up to enclosing main reference {}", mainRef);
			}

			// Update part of the main doc (which must already exist)
			String key = BsonFormatter.dottedFieldNameOf(target, mainRef);
			LOGGER.debug("| Pre-delete field {} in {}", key, mainRef);
			BsonDocument preDelete = new BsonDocument("$unset", new BsonDocument(key, BsonNull.VALUE));
			doUpdate(preDelete, standardPreconditions(target, mainRef, filter));
			LOGGER.debug("| Set field {} in {}: {}", key, mainRef, value);
			BsonDocument mainUpdate = new BsonDocument("$set", new BsonDocument(key, value));
			doUpdate(mainUpdate, standardPreconditions(target, mainRef, filter));

			LOGGER.debug("| Bump revision on root document");
			doUpdate(blankUpdateDoc(), documentFilter(rootRef));
		}
	}

	private <T> void doDelete(Reference<T> target) {
		collection.ensureTransactionStarted();
		deletePartsUnder(target);
		Reference<?> mainRef = mainRef(target);
		if (mainRef.equals(target)) {
			// Delete the whole document
			if (settings.experimental().orphanDocumentMode() == OrphanDocumentMode.HASTY) {
				LOGGER.debug("Skipping deleting document({}) in {} mode", target, OrphanDocumentMode.HASTY);
			} else {
				throw new NotYetImplementedException("Earnest mode not yet implemented");
			}

			assert !mainRef.path().isEmpty(): "Can't delete the root reference";
			// Move up to the parent document to delete the "true" stub
			mainRef = mainRef(mainRef.enclosingReference(Object.class));
			LOGGER.debug("Move up to enclosing main reference {}", mainRef);
		}
		if (doUpdate(deletionDoc(target, mainRef), standardPreconditions(target, mainRef, documentFilter(mainRef)))) {
			if (!rootRef.equals(mainRef)) {
				LOGGER.debug("Deletion succeeded; bumping revision number in root document");
				doUpdate(blankUpdateDoc(), documentFilter(rootRef));
			}
		} else {
			LOGGER.debug("Deletion had no effect; aborting transaction");
			collection.abortTransaction();
		}
	}

	private boolean preconditionFailed(Reference<Identifier> precondition, Identifier requiredValue) {
		Reference<?> mainRef = mainRef(precondition);
		BsonDocument filter = documentFilter(mainRef)
			.append(BsonFormatter.dottedFieldNameOf(precondition, mainRef), new BsonString(requiredValue.toString()));
		LOGGER.debug("Precondition filter: {}", filter);
		boolean result = !documentExists(filter);
		if (result) {
			LOGGER.debug("Precondition fail: {} != {}", precondition, requiredValue);
		}
		return result;
	}

	private boolean documentExists(BsonDocument filter) {
		return 0 != collection.countDocuments(filter, new CountOptions().limit(1));
	}

	/**
	 * @return {@link Reference} to the bosk object corresponding to the document
	 * that contains the given <code>target</code>.
	 */
	private Reference<?> mainRef(Reference<?> target) {
		if (target.path().isEmpty()) {
			return rootRef;
		}

		// The main reference is the "deepest" one that matches the target reference.
		// graftPoints is in descending order of depth.
		// TODO: This could be done more efficiently, perhaps using a trie
		int targetPathLength = target.path().length();
		for (var candidateContainer: graftPoints) {
			int containerPathLength = candidateContainer.path().length();
			if (containerPathLength <= targetPathLength - 1) {
				if (candidateContainer.path().matchesPrefixOf(target.path())) {
					try {
						return candidateContainer.boundBy(target.path()).then(Object.class,
							// The container plus one segment from the target ref
							target.path().segment(containerPathLength)
						);
					} catch (InvalidTypeException e) {
						throw new AssertionError("Unexpected exception forming mainRef from container " + candidateContainer + " and target " + target);
					}
				}
			}
		}
		return rootRef;
	}

	@Override
	public BsonDocument rootDocumentsFilter() {
		return switch (format.tenancyFormat()) {
			case NONE ->
				new BsonDocument("_id", new BsonString("|"));
			case ID_PREFIX ->
				// Whether a document is a root document cannot be determined by a prefix
				// on the _id field, so the index won't work, and we'll be doing a table scan.
				// At least with the path field, the search is simpler, and we could in principle
				// speed it up with an index if need be.
				new BsonDocument("path", ROOT_PATH);
		};
	}

	private BsonDocument documentFilter(Reference<?> docRef) {
		return new BsonDocument("_id", new BsonString(tenantPrefix(context.getEstablishedTenant()) + docBsonPath(docRef, rootRef)));
	}

	private <T> BsonDocument standardRootPreconditions(Reference<T> target) {
		return standardPreconditions(target, rootRef, documentFilter(rootRef));
	}

	private <T> BsonDocument standardPreconditions(Reference<T> target, Reference<?> startingRef, BsonDocument filter) {
		if (!target.path().equals(startingRef.path())) {
			String enclosingObjectKey = BsonFormatter.dottedFieldNameOf(target.enclosingReference(Object.class), startingRef);
			BsonDocument condition = new BsonDocument("$type", new BsonString("object"));
			filter.put(enclosingObjectKey, condition);
			LOGGER.debug("| Precondition: {} {}", enclosingObjectKey, condition);
		}
		return filter;
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
			long matchedCount = result.getMatchedCount();
			if (matchedCount == 0) {
				LOGGER.debug("| -> No documents were updated; double-checking that the root document still exists");
				try (var cursor = collection.find(documentFilter(rootRef)).limit(1).cursor()) {
					if (!cursor.hasNext()) {
						switch (context.getEstablishedTenant()) {
							case TenantId t -> throw new NoSuchTenantException(t, "No root document for " + t);
							case None _ -> throw new IllegalStateException("Root document disappeared");
						}
					}
				}
			}
			return matchedCount >= 1;
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
	private void replaceUpdatedFields(Reference<?> mainRef, @Nullable BsonDocument updatedFields, List<BsonDocument> subParts, OperationType operationType) throws UnprocessableEventException {
		if (updatedFields != null) {
			boolean alreadyUsedSubparts = false;
			for (Entry<String, BsonValue> entry : updatedFields.entrySet()) {
				String dottedName = entry.getKey();
				if (dottedName.startsWith(DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = BsonFormatter.referenceTo(dottedName, mainRef);
					} catch (InvalidTypeException e) {
						logNonexistentField(dottedName, e);
						continue;
					}

					if (alreadyUsedSubparts) {
						throw new IllegalStateException("Not expecting an update event that changes multiple state fields");
					} else {
						alreadyUsedSubparts = true;
					}

					BsonValue replacementValue = entry.getValue();
					if (replacementValue instanceof BsonDocument) {
						LOGGER.debug("Replacement value is a document; gather along with {} subparts", subParts.size());
						String mainID = tenantPrefix(context.getEstablishedTenant()) + docBsonPath(ref, mainRef);
						BsonDocument mainDocument = new BsonDocument()
							.append("_id", new BsonString(mainID))
							.append("state", replacementValue);
						ArrayList<BsonDocument> parts = new ArrayList<>(subParts.size() + 1);
						parts.addAll(subParts);
						parts.add(mainDocument);

						replacementValue = gather(parts);
					} else if (subParts.isEmpty()) {
						LOGGER.debug("Replacement value is scalar: {}", replacementValue);
					} else if (TRUE.equals(replacementValue)) {
						LOGGER.debug("Replacement value is stub; gather {} subparts", subParts.size());
						replacementValue = gather(subParts);
					} else {
						throw new UnprocessableEventException("Scalar " + replacementValue + " has subparts:\n\t" + subParts, operationType);
					}

					LOGGER.debug("| Replace {}", ref);
					LOGGER.trace("| New value: {}", replacementValue);
					Object replacement = formatter.bsonValue2object(replacementValue, ref);
					downstream.submitReplacement(ref, replacement);
					LOGGER.trace("| Done replacing {}", ref);
				}
			}
		}
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitDeletion submitDeletion}</code>
	 * for each removed field.
	 */
	private void deleteRemovedFields(Reference<?> mainRef, @Nullable List<String> removedFields, OperationType operationType) throws UnprocessableEventException {
		if (removedFields != null) {
			for (String dottedName : removedFields) {
				if (dottedName.startsWith(DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = BsonFormatter.referenceTo(dottedName, mainRef);
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

	private <T> void deletePartsUnder(Reference<T> target) {
		// This whole method is pretty "best-effort" right now. More work to do if we really want to be EARNEST
		Reference<?> mainRef = mainRef(target);
		if (mainRef.equals(target)) {
			if (settings.experimental().orphanDocumentMode() == OrphanDocumentMode.HASTY) {
				LOGGER.debug("Skipping deletePartsUnder({}) in {} mode", target, OrphanDocumentMode.HASTY);
			} else {
				String prefix;
				if (mainRef.path().isEmpty()) {
					prefix = "|";
				} else {
					prefix = docBsonPath(mainRef, rootRef) + "|";
				}

				// Every doc whose ID starts with the prefix and has at least one more character
				Bson filter = regex("_id", "^" + Pattern.quote(prefix) + ".");

				DeleteResult result = collection.deleteMany(filter);
				LOGGER.debug("deletePartsUnder({}) result: {} filter: {}", mainRef, result, filter);
			}
		} else {
			// TODO!
			assert settings.experimental().orphanDocumentMode() == OrphanDocumentMode.HASTY;
			LOGGER.debug("Skipping deletePartsUnder({}) because mainRef is different: {}", target, mainRef);
		}
	}

	/**
	 * @param value is mutated to stub-out the parts written to the database
	 */
	private <T> void upsertAndRemoveSubParts(Reference<T> target, BsonDocument value, String idPrefix) {
		List<BsonDocument> allParts = scatter(target, value, idPrefix);
		// NOTE: `value` has now been mutated so the parts have been stubbed out

		List<BsonDocument> subParts = allParts.subList(0, allParts.size() - 1);

		LOGGER.debug("Document has {} sub-parts", subParts.size());
		for (BsonDocument part: subParts) {
			// scatter() already prepended idPrefix to the _id; don't prepend it again
			BsonString id = part.getString("_id");
			BsonDocument filter = new BsonDocument("_id", id);
			LOGGER.debug("Pre-delete sub-part: filter={}", filter);
			collection.deleteOne(filter);
			LOGGER.debug("Insert sub-part: filter={} replacement={}", filter, part);
			InsertOneResult result = collection.insertOne(part); // we _must_ get the precise full document for sub-parts in the event stream, or we can't form the whole
			LOGGER.debug("| Insert result: {}", result);
		}

	}

	@Override
	public String toString() {
		return description;
	}

	private static final EnumSet<OperationType> OPERATIONS_TO_INCLUDE_IN_GATHER = EnumSet.of(INSERT);
	private static final Logger LOGGER = LoggerFactory.getLogger(PandoFormatDriver.class);
}
