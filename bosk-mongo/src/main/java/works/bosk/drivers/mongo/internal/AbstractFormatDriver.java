package works.bosk.drivers.mongo.internal;

import com.mongodb.ReadConcern;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.result.UpdateResult;
import java.io.IOException;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskConfig.TenancyModel;
import works.bosk.BoskConfig.TenancyModel.Fixed;
import works.bosk.BoskConfig.TenancyModel.None;
import works.bosk.BoskConfig.TenancyModel.Persistent;
import works.bosk.BoskContext;
import works.bosk.BoskContext.Tenant;
import works.bosk.BoskContext.Tenant.Established;
import works.bosk.BoskContext.Tenant.TenantId;
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
import works.bosk.util.PerTenant;
import works.bosk.util.PerTenant.MultiTenant;
import works.bosk.util.PerTenant.NoTenant;
import works.bosk.util.TunneledCheckedException;

import static com.mongodb.ReadConcern.LOCAL;
import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static com.mongodb.client.model.changestream.OperationType.REPLACE;
import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;
import static works.bosk.drivers.mongo.internal.BsonFormatter.dottedFieldNameOf;
import static works.bosk.drivers.mongo.internal.Formatter.REVISION_BEFORE_ANY;
import static works.bosk.drivers.mongo.internal.Formatter.REVISION_ZERO;
import static works.bosk.drivers.mongo.internal.Formatter.getTenantFromDocumentId;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;

abstract non-sealed class AbstractFormatDriver<R extends StateTreeNode> implements FormatDriver<R> {
	final RootReference<R> rootRef;
	final BoskContext context;
	final TenancyModel tenancyModel;
	final Formatter formatter;
	final TransactionalCollection collection;
	final BoskDriver downstream;
	final long flushTimeoutMS;
	final Supplier<EntireState<R>> entireStateSupplier;

	/**
	 * Null only during initialization, until {@link #ensureFlushLocksInitialized},
	 * and only used after that point.
	 * (Therefore, there's no need for null tests before using this:
	 * an NPE is an acceptable outcome if someone ever violates these rules.)
	 */
	final AtomicReference<PerTenant<FlushLock>> flushLocks = new AtomicReference<>(null);

	final FlushLock contentsFlushLock;

	public AbstractFormatDriver(
		RootReference<R> rootRef,
		BoskContext context,
		TenancyModel tenancyModel,
		Formatter formatter,
		TransactionalCollection collection,
		BoskDriver downstream,
		long flushTimeoutMS,
		Supplier<EntireState<R>> entireStateSupplier
	) {
		this.rootRef = rootRef;
		this.context = context;
		this.tenancyModel = tenancyModel;
		this.formatter = formatter;
		this.collection = collection;
		this.downstream = downstream;
		this.flushTimeoutMS = flushTimeoutMS;
		this.entireStateSupplier = entireStateSupplier;
		this.contentsFlushLock = new FlushLock(REVISION_BEFORE_ANY.longValue(), flushTimeoutMS);
	}

	@Override
	public MongoStatus readStatus() {
		try {
			PerTenant<BsonStateAndMetadata> dbStates = loadBsonStateAndMetadata();
			var entireState = entireStateSupplier.get();
			var inMemoryBsonValues = PerTenant.from(entireState,
				r -> formatter.object2bsonValue(r, rootRef.targetType()));
			BsonComparator comp = new BsonComparator();
			var stateStatuses = dbStates.map((Established tenant, BsonStateAndMetadata dbState) -> {
				BsonValue inMemory = switch (inMemoryBsonValues) {
					case NoTenant<BsonValue>(var v) -> v;
					case MultiTenant<BsonValue> m -> m.get(tenant);
				};
				BsonDocument loadedBsonState = dbState.state();
				return new StateStatus(
					dbState.revision().longValue(),
					formatter.bsonValueBinarySize(loadedBsonState),
					comp.difference(inMemory, loadedBsonState)
				);
			});
			return new MongoStatus(
				null,
				null, // MainDriver should fill this in
				stateStatuses
			);
		} catch (InvalidCollectionContentsException e) {
			return new MongoStatus(
				e.toString(),
				null,
				null
			);
		}
	}

	@Override
	public PerTenant<StateAndMetadata<R>> loadAllState() throws IOException, InvalidCollectionContentsException {
		try {
			PerTenant<BsonStateAndMetadata> bsonStateAndMetadata = loadBsonStateAndMetadata();
			ensureFlushLocksInitialized(bsonStateAndMetadata);
			return bsonStateAndMetadata.map((Established _, BsonStateAndMetadata bsm) -> {
				if (bsm.state() == null) {
					throw new TunneledCheckedException(new IOException("No existing state in document"));
				}

				R root = formatter.document2object(bsm.state(), rootRef);
				BsonInt64 revision = bsm.revision() == null ? REVISION_ZERO : bsm.revision();
				MapValue<String> diagnosticAttributes = bsm.diagnosticAttributes() == null
					? MapValue.empty() // It's not clear what missing attributes mean, but using null here would have the effect of leaving the old attributes in place, which seems flaky
					: formatter.decodeDiagnosticAttributes(bsm.diagnosticAttributes());

				return new StateAndMetadata<>(root, revision, diagnosticAttributes);
			});
		} catch (TunneledCheckedException e) {
			try {
				throw e.getCause();
			} catch (IOException | RuntimeException | Error cause) {
				throw cause;
			} catch (Throwable t) {
				throw e;
			}
		}
	}

	@Override
	public void hasBeenApplied(PerTenant<StateAndMetadata<R>> contents) {
		contents.forEach((tenant, stateAndMetadata) ->
			finishedRevision(tenant, stateAndMetadata.revision()));
	}

	/**
	 * Converts a {@link PerTenant} to the variant appropriate for the {@link #tenancyModel}.
	 * For {@code None} tenancy, {@code NoTenant} is returned as-is.
	 * For {@code Fixed} tenancy, {@code NoTenant} is converted to
	 * {@link MultiTenant} with the fixed tenant ID.
	 */
	protected <T> PerTenant<T> normalizePerTenant(PerTenant<T> contents) {
		return switch (contents) {
			case NoTenant<T>(var value) -> switch (tenancyModel) {
				case None _ -> contents;
				case Fixed(var id) -> MultiTenant.singleton(Tenant.setTo(id), value);
				case Persistent _ -> throw new AssertionError(
					"Should not have NoTenant contents with Persistent tenancy");
			};
			case MultiTenant<T> _ -> contents;
		};
	}

	/**
	 * Must be called before {@link #hasBeenApplied} or any event processing.
	 */
	protected void ensureFlushLocksInitialized(PerTenant<?> contents) {
		flushLocks.compareAndSet(null, switch (contents) {
			case NoTenant<?> _ -> switch (tenancyModel) {
				case None _ -> NoTenant.just(newFlushLock());
				case Fixed(var id) -> MultiTenant.singleton(Tenant.setTo(id), newFlushLock());
				case Persistent _ -> throw new AssertionError(
					"Should not have NoTenant contents with Persistent tenancy");
			};
			case MultiTenant<?> m -> {
				SortedMap<TenantId, FlushLock> locks = new TreeMap<>();
				for (var tenantId : m.values().keySet()) {
					locks.put(tenantId, newFlushLock());
				}
				yield new MultiTenant<>(locks);
			}
		});
	}

	private @NonNull FlushLock newFlushLock() {
		return new FlushLock(REVISION_BEFORE_ANY.longValue(), flushTimeoutMS);
	}

	/**
	 * Low-level read of the database contents, with only the minimum interpretation
	 * necessary to determine what the various parts correspond to.
	 *
	 * @return the contents of the database; fields of the returned
	 * record can be null if they don't exist in the database.
	 */
	abstract PerTenant<BsonStateAndMetadata> loadBsonStateAndMetadata() throws InvalidCollectionContentsException;

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

	protected boolean shouldSkip(Established tenant, BsonInt64 revision) {
		FlushLock lock = flushLocks.get().get(tenant);
		// When lock is null, we don't have a lock for this tenant, so this revision is always interesting.
		return lock != null && lock.alreadySeen(revision);
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

	protected void finishedRevision(Established tenant, BsonInt64 revision) {
		PerTenant<FlushLock> f = flushLocks.get();
		FlushLock lock = f.get(tenant);
		if (lock == null) {
			flushLocks.compareAndSet(f, switch (f) {
				case MultiTenant<FlushLock> m when tenant instanceof TenantId t -> m.with(t, new FlushLock(revision.longValue(), flushTimeoutMS));
				default -> throw new IllegalStateException("Cannot add tenant " + tenant);
			});
		} else {
			lock.finishedRevision(revision);
		}
	}

	protected void finishedContentsRevision(BsonInt64 revision) {
		if (revision != null) {
			contentsFlushLock.finishedRevision(revision);
		}
	}

	protected BsonInt64 readContentsRevision() {
		try (MongoCursor<BsonDocument> cursor = collection
			.withReadConcern(LOCAL)
			.find(new BsonDocument("_id", CONTENTS_ID))
			.projection(fields(include("_id", DocumentFields.revision.name())))
			.cursor()) {
			if (cursor.hasNext()) {
				return cursor.next().getInt64(DocumentFields.revision.name(), REVISION_ZERO);
			}
			return REVISION_ZERO;
		}
	}

	/**
	 * @return the {@link Tenant} that should be established before processing
	 * a document with the given {@code id}
	 */
	protected @NonNull Established tenantFor(BsonString id) {
		return switch (tenancyModel) {
			case None _ -> Tenant.NONE;
			case Fixed(var fixedId) -> Tenant.setTo(fixedId);
			case Persistent _ -> getTenantFromDocumentId(id);
		};
	}

	/**
	 * @return cursor giving the {@code _id} and {@code revision}
	 * for all documents that have a revision field,
	 * read using read concern {@link ReadConcern#LOCAL LOCAL}.
	 */
	protected MongoCursor<BsonDocument> revisionDocumentCursor() {
		return collection
			.withReadConcern(LOCAL)
			.find(rootDocumentsFilter())
			.projection(fields(include("_id", DocumentFields.revision.name())))
			.cursor();
	}

	/**
	 * @return all potentially relevant revision numbers found in the database
	 * @throws RevisionFieldDisruptedException if unexpected database contents make it impossible to determine the revision number
	 */
	abstract @NonNull PerTenant<BsonInt64> readRevisionNumbers() throws RevisionFieldDisruptedException;

	@Override
	public void flush() throws IOException, InterruptedException {
		var revisions = readRevisionNumbers();
		try {
			flushLocks.get().forEach((tenant, lock) -> {
				BsonInt64 revision = revisions.get(tenant);
				try {
					if (revision == null) {
						// Tenant has been deleted from the database; nothing to flush
						return;
					} else {
						lock.awaitRevision(revision);
					}
				} catch (InterruptedException | FlushFailureException e) {
					throw new TunneledCheckedException(e);
				}
			});
		} catch (TunneledCheckedException e) {
			try {
				throw e.getCause();
			} catch (IOException | InterruptedException cause) {
				throw cause;
			} catch (Throwable ex) {
				throw e;
			}
		}
		additionalFlushWaits();
		LOGGER.debug("| Flush downstream");
		downstream.flush();
	}

	/**
	 * Override in subclasses to add additional waits during {@link #flush()}.
	 */
	protected void additionalFlushWaits() throws IOException, InterruptedException {
		// Default: no additional waits
	}

	@Override
	public void close() {
		LOGGER.debug("+ close()");
		flushLocks.get().forEach((_, flushLock) -> flushLock.close());
		contentsFlushLock.close();
	}

	protected void writeManifest(Manifest manifest) {
		BsonDocument doc = new BsonDocument("_id", requireNonNull(MANIFEST_ID));
		doc.putAll((BsonDocument) formatter.object2bsonValue(manifest, Manifest.class));
		BsonDocument filter = new BsonDocument("_id", MANIFEST_ID);
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

	protected boolean isManifestID(BsonValue documentId) {
		return MANIFEST_ID.equals(documentId);
	}

	protected boolean isContentsID(BsonValue documentId) {
		return CONTENTS_ID.equals(documentId);
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
	static final BsonString CONTENTS_ID = new BsonString("!contents");
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractFormatDriver.class);

}
