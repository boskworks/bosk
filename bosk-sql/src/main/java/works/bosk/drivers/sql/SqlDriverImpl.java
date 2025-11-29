package works.bosk.drivers.sql;

import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jooq.Record;
import org.jooq.TableField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.StringNode;
import tools.jackson.databind.type.TypeFactory;
import works.bosk.BoskDiagnosticContext;
import works.bosk.BoskDriver;
import works.bosk.BoskInfo;
import works.bosk.Identifier;
import works.bosk.MapValue;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.RootReference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.sql.schema.BoskTable;
import works.bosk.drivers.sql.schema.ChangesTable;
import works.bosk.drivers.sql.schema.Schema;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NotYetImplementedException;
import works.bosk.jackson.JsonNodeSurgeon;
import works.bosk.jackson.JsonNodeSurgeon.NodeInfo;
import works.bosk.jackson.JsonNodeSurgeon.NodeLocation.NonexistentParent;
import works.bosk.jackson.JsonNodeSurgeon.NodeLocation.Root;

import static java.lang.Math.min;
import static java.lang.Math.multiplyExact;
import static java.lang.System.currentTimeMillis;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.primaryKey;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.using;
import static works.bosk.logging.MappedDiagnosticContext.MDCScope;
import static works.bosk.logging.MappedDiagnosticContext.setupMDC;

class SqlDriverImpl implements SqlDriver {
	private final SqlDriverSettings settings;
	private final BoskDriver downstream;
	private final RootReference<?> rootRef;
	private final String boskName;
	private final Identifier boskID;
	private final BoskDiagnosticContext diagnosticContext;
	private final ConnectionSource connectionSource;
	private final ObjectMapper mapper;
	private final JsonNodeSurgeon surgeon = new JsonNodeSurgeon();

	private final AtomicBoolean isOpen = new AtomicBoolean(true);

	// jOOQ references
	private final TableField<Record, String> DIAGNOSTICS;
	private final ChangesTable CHANGES;
	private final TableField<Record, Long> REVISION;
	private final TableField<Record, String> REF;
	private final TableField<Record, String> NEW_STATE;
	private final TableField<Record, String> STATE;
	private final BoskTable BOSK;
	private final TableField<Record, String> ID;

	private final ScheduledExecutorService listener;

	private volatile String epoch;

	private final AtomicLong lastChangeSubmittedDownstream = new AtomicLong(-1);

	SqlDriverImpl(
		SqlDriverSettings settings,
		ConnectionSource cs,
		BoskInfo<?> bosk,
		ObjectMapper mapper,
		BoskDriver downstream
	) {
		this.settings = settings;
		this.downstream = requireNonNull(downstream);
		this.rootRef = requireNonNull(bosk.rootReference());
		this.boskName = bosk.name();
		this.boskID	= bosk.instanceID();
		this.diagnosticContext = requireNonNull(bosk.diagnosticContext());
		this.mapper = requireNonNull(mapper);
		this.connectionSource = () -> {
			Connection result = cs.get();
			// autoCommit is an idiotic default
			result.setAutoCommit(false);
			return result;
		};

		// Set up jOOQ references
		Schema schema = new Schema();
		REF = schema.REF;
		NEW_STATE = schema.NEW_STATE;
		REVISION = schema.REVISION;
		CHANGES = schema.CHANGES;
		DIAGNOSTICS = schema.DIAGNOSTICS;
		STATE = schema.STATE;
		BOSK = schema.BOSK;
		ID = schema.ID;

		// Kick off listener thread
		listener = Executors.newScheduledThreadPool(1, r ->
			new Thread(r, "SQL listener \""
				+ bosk.name()
				+ "\" "
				+ bosk.instanceID())
		);
	}

	private void listenForChanges() {
		try (MDCScope _ = setupMDC(boskName, boskID)) {
			if (!isOpen.get()) {
				LOGGER.debug("Already closed");
				return;
			}
			LOGGER.trace("Polling for changes");
			try {
				try (var c = connectionSource.get()) {
					var rs = using(c)
						.select(REF, NEW_STATE, DIAGNOSTICS, CHANGES.EPOCH, REVISION)
						.from(CHANGES)
						.where(REVISION.gt(lastChangeSubmittedDownstream.get()))
						.fetch();
					for (var r: rs) {
						var ref = r.get(REF);
						var newState = r.get(NEW_STATE);
						var diagnostics = r.get(DIAGNOSTICS);
						String epoch = r.get(CHANGES.EPOCH);
						long changeID = r.get(REVISION);

						if (!epoch.equals(this.epoch)) {
							throw new NotYetImplementedException("Epoch changed!");
						}

						if (LOGGER.isTraceEnabled()) {
							record Change(String ref, String newState, String diagnostics){}
							LOGGER.trace("Received change {}: {}", changeID, new Change(ref, newState, diagnostics));
						} else {
							LOGGER.debug("Received change {}: {} (diagnostics: {})", changeID, ref, diagnostics);
						}

						MapValue<String> diagnosticAttributes;
						if (diagnostics == null) {
							diagnosticAttributes = MapValue.empty();
						} else {
							try {
								diagnosticAttributes = mapper.readerFor(mapValueType(String.class)).readValue(diagnostics);
							} catch (JacksonException e) {
								LOGGER.error("Unable to parse diagnostic attributes; ignoring", e);
								diagnosticAttributes = MapValue.empty();
							}
						}
						try (var _ = diagnosticContext.withOnly(diagnosticAttributes)) {
							Reference<Object> target = rootRef.then(Object.class, Path.parse(ref));
							Object newValue;
							if (newState == null) {
								newValue = null;
							} else {
								newValue = mapper.readerFor(typeFactory.constructType(target.targetType()))
									.readValue(newState);
							}
							submitDownstream(target, newValue, changeID);
						} catch (JacksonException e) {
							throw new NotYetImplementedException("Error parsing notification", e);
						} catch (InvalidTypeException e) {
							throw new NotYetImplementedException("Invalid object reference: \"" + ref + "\"", e);
						}

					}
				} catch (SQLException e) {
					throw new NotYetImplementedException(e);
				}
			} catch (RuntimeException e) {
				if (isOpen.get()) {
					LOGGER.warn("Change processing exited unexpectedly; will restart", e);
				} else {
					LOGGER.debug("Driver is closed; exiting change processing loop", e);
				}
				throw e;
			}
		}
	}

	@SuppressWarnings({"rawtypes","unchecked"})
	private void submitDownstream(Reference target, Object newValue, long changeID) {
		if (newValue == null) {
			LOGGER.debug("Downstream submitDeletion({})", target);
			downstream.submitDeletion(target);
		} else {
			LOGGER.debug("Downstream submitReplacement({}, ...)", target);
			downstream.submitReplacement(target, newValue);
		}
		long prev = lastChangeSubmittedDownstream.getAndSet(changeID);
		if (prev >= changeID) {
			// TODO: Re-initialize to recover
			LOGGER.debug("Change ID did not increase from {} to {}", prev, changeID);
			throw new NotYetImplementedException("Change ID did not increase: changed from " + prev + " to " + changeID);
		} else {
			LOGGER.debug("Change ID increased from {} to {}", prev, changeID);
		}
	}

	/**
	 * Best-effort cleanup, mainly meant for testing.
	 * The driver might still perform a small number of asynchronous actions after this method returns.
	 */
	@Override
	public void close() {
		if (isOpen.getAndSet(false)) {
			LOGGER.debug("Closing");
			if (LOGGER.isTraceEnabled()) {
				try (var c = connectionSource.get()) {
					var rs = using(c).select(CHANGES.asterisk()).from(CHANGES).fetch();
					LOGGER.trace("\n=== Final change table contents ===\n{}", rs);
				} catch (SQLException e) {
					throw new NotYetImplementedException(e);
				}
			}
			listener.shutdownNow();
		}
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		// TODO: Consider a disconnected mode where we delegate downstream if something goes wrong
		try (MDCScope _ = setupMDC(boskName, boskID)) {
			LOGGER.debug("initialRoot({})", rootType);
			try (
				var connection = connectionSource.get()
			){
				// TODO: It seems wrong to schedule the listener loop here. It should be in the constructor.
				ensureTablesExist(connection);
				StateTreeNode result;
				var stateAndEpoch = loadStateAndEpoch(connection);
				if (stateAndEpoch == null) {
					LOGGER.debug("No current state; initializing {} table from downstream", BOSK);
					this.epoch = UUID.randomUUID().toString();
					result = downstream.initialRoot(rootType);
					String stateJson = mapper.writeValueAsString(result);

					using(connection)
						.insertInto(BOSK).columns(ID, STATE, BOSK.EPOCH)
						.values("current", stateJson, epoch)
						.onConflictDoNothing()
						.execute();
					long changeID = insertChange(connection, rootRef, stateJson);
					connection.commit();
					lastChangeSubmittedDownstream.getAndSet(changeID); // Not technically "submitted"
				} else {
					LOGGER.debug("Database state exists; initializing downstream from {} table", BOSK);
					result = resetBoskState(rootType, stateAndEpoch, connection);
				}
				listener.scheduleWithFixedDelay(this::listenForChanges, 0, settings.timescaleMS(), MILLISECONDS);
				return result;
			} catch (SQLException e) {
				throw new NotYetImplementedException(e);
			}
		}
	}

	private record StateAndEpoch(String state, String epoch){}

	private StateAndEpoch loadStateAndEpoch(Connection connection) {
		return using(connection)
			.select(STATE, BOSK.EPOCH)
			.from(BOSK)
			.where(ID.eq("current"))
			.fetchOneInto(StateAndEpoch.class);
	}

	private synchronized StateTreeNode resetBoskState(Type rootType, StateAndEpoch stateAndEpoch, Connection connection) throws SQLException {
		StateTreeNode result;
		this.epoch = stateAndEpoch.epoch;
		long currentChangeID;
		try {
			currentChangeID = latestChangeID(connection);
		} catch (EpochMismatchException e) {
			throw new AssertionError("Epoch was just set, so it shouldn't mismatch in the same transaction", e);
		}
		JavaType valueType = typeFactory.constructType(rootType);
		result = mapper.readValue(stateAndEpoch.state, valueType);
		this.lastChangeSubmittedDownstream.set(-1);
		connection.commit();
		submitDownstream(rootRef, result, currentChangeID);
		return result;
	}

	private void ensureTablesExist(Connection connection) throws SQLException {
		using(connection)
			.createTableIfNotExists(CHANGES)
			.columns(CHANGES.EPOCH, REVISION, REF, NEW_STATE, DIAGNOSTICS)
			.constraints(primaryKey(REVISION))
			.execute();

		using(connection)
			.createTableIfNotExists(BOSK)
			.columns(ID, BOSK.EPOCH, STATE)
			.constraints(primaryKey(ID))
			.execute();

		// When we say "ensure tables exist", we mean it
		connection.commit();
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		try (MDCScope _ = setupMDC(boskName, boskID)) {
			LOGGER.debug("submitReplacement({}, {})", target, newValue);
			try (
				var connection = connectionSource.get()
			) {
				// TODO optimization: no need to read the current state for root
				replaceAndCommit(readState(connection), target, newValue, connection);
			} catch (SQLException e) {
				throw new NotYetImplementedException(e);
			}
		}
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		try (MDCScope _ = setupMDC(boskName, boskID)) {
			LOGGER.debug("submitConditionalReplacement({}, {}, {}, {})", target, newValue, precondition, requiredValue);
			try (
				var connection = connectionSource.get()
			) {
				JsonNode state = readState(connection);
				if (isMatchingTextNode(precondition, requiredValue, state)) {
					replaceAndCommit(state, target, newValue, connection);
				}
			} catch (SQLException e) {
				throw new NotYetImplementedException(e);
			}
		}
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		try (MDCScope _ = setupMDC(boskName, boskID)) {
			LOGGER.debug("submitConditionalCreation({}, {})", target, newValue);
			try (
				var connection = connectionSource.get()
			) {
				JsonNode state = readState(connection);
				NodeInfo node = surgeon.nodeInfo(state, target);
				if (surgeon.node(node.valueLocation(), state) == null) {
					replaceAndCommit(state, target, newValue, connection);
				}
			} catch (SQLException e) {
				throw new NotYetImplementedException(e);
			}
		}
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		try (MDCScope _ = setupMDC(boskName, boskID)) {
			LOGGER.debug("submitDeletion({})", target);
			try (
				var connection = connectionSource.get()
			) {
				replaceAndCommit(readState(connection), target, null, connection);
			} catch (SQLException e) {
				throw new NotYetImplementedException(e);
			}
		}
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		try (MDCScope _ = setupMDC(boskName, boskID)) {
			LOGGER.debug("submitConditionalDeletion({}, {}, {})", target, precondition, requiredValue);
			try (
				var connection = connectionSource.get()
			) {
				JsonNode state = readState(connection);
				if (isMatchingTextNode(precondition, requiredValue, state)) {
					replaceAndCommit(state, target, null, connection);
				}
			} catch (SQLException e) {
				throw new NotYetImplementedException(e);
			}
		}
	}

	private boolean isMatchingTextNode(Reference<Identifier> precondition, Identifier requiredValue, JsonNode state) {
		return surgeon.valueNode(state, precondition) instanceof StringNode text
			&& Objects.equals(text.asString(), requiredValue.toString());
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		try (MDCScope _ = setupMDC(boskName, boskID)) {
			try (
				var connection = connectionSource.get()
			) {
				long currentChangeID = latestChangeID(connection);
				LOGGER.debug("flush({})", currentChangeID);

				// Exponential backoff starting from timescaleMS
				long sleepTime = settings.timescaleMS();
				long sleepDeadline = currentTimeMillis() + multiplyExact(sleepTime, settings.patienceFactor());
				while (lastChangeSubmittedDownstream.get() < currentChangeID) {
					long sleepBudget = sleepDeadline - currentTimeMillis();
					if (sleepBudget <= 0) {
						throw new FlushFailureException("Timed out waiting for change #" + currentChangeID);
					}
					LOGGER.debug("Will retry");
					Thread.sleep(min(sleepBudget, sleepTime));
					sleepTime *= 2;
				}
			} catch (SQLException e) {
				throw new FlushFailureException(e);
			} catch (EpochMismatchException e) {
				LOGGER.debug("Epoch mismatch: reload state from database");
				try (var c = connectionSource.get()) {
					resetBoskState(rootRef.targetType(), loadStateAndEpoch(c), c);
				} catch (SQLException | RuntimeException ex) {
					throw new FlushFailureException(ex);
				}
			} catch (RuntimeException e) {
				throw new FlushFailureException("Unexpected error while flushing", e);
			}
			downstream.flush();
		}
	}

	/**
	 * @param state    may be mutated!
	 * @param newValue if null, this is a delete
	 */
	private <T> void replaceAndCommit(JsonNode state, Reference<T> target, T newValue, Connection connection) throws SQLException {
		NodeInfo node = surgeon.nodeInfo(state, target);
		switch (node.replacementLocation()) {
			case Root _ -> {
				if (newValue == null) {
					throw new NotYetImplementedException("Cannot delete root");
				}
				String json = mapper.writeValueAsString(newValue);
				long revision = insertChange(connection, target, json);
				using(connection)
					.update(BOSK)
					.set(STATE, json)
					.execute();
				connection.commit();
				LOGGER.debug("{}: replaced root", revision);
			}
			case NonexistentParent _ -> {
				// Modifying a node with a nonexistent parent is a no-op
				LOGGER.debug("--: nonexistent parent for {}", target);
			}
			default -> {
				JsonNode newNode;
				if (newValue == null) {
					newNode = null;
					surgeon.deleteNode(node);
				} else {
					newNode = mapper.valueToTree(newValue);
					surgeon.replaceNode(node, surgeon.replacementNode(node, target.path().lastSegment(), ()->newNode));
				}
				String nodeJson, stateJson;
				try {
					nodeJson = mapper.writeValueAsString(newNode);
					stateJson = mapper.writeValueAsString(state);
				} catch (JacksonException e) {
					throw new NotYetImplementedException(e);
				}
				insertChange(connection, target, nodeJson);
				using(connection)
					.update(BOSK)
					.set(STATE, stateJson)
					.execute();
				connection.commit();
				LOGGER.debug("--: replaced {}", target);
			}
		}
	}

	private long insertChange(Connection c, Reference<?> ref, String newValue) {
		try {
			return using(c)
				.insertInto(CHANGES).columns(CHANGES.EPOCH, REF, NEW_STATE, DIAGNOSTICS)
				.values(epoch, ref.pathString(), newValue, mapper.writeValueAsString(diagnosticContext.getAttributes()))
				.returning(REVISION)
				.fetchOptional(REVISION)
				.orElseThrow(()->new NotYetImplementedException("No change inserted"));
		} catch (JacksonException e) {
			throw new NotYetImplementedException(e);
		}
	}

	private JsonNode readState(Connection connection) {
		String json;
		try {
			json = using(connection)
				.select(STATE)
				.from(BOSK)
				.fetchOptional(STATE)
				.orElseThrow(() -> new NotYetImplementedException("No state found"));
		} catch (RuntimeException e) {
			throw new IllegalStateException("Unexpected error reading state from database", e);
		}
		try {
			return mapper.readTree(json);
		} catch (JacksonException e) {
			throw new IllegalStateException("Unable to parse database contents", e);
		}
	}

	private long latestChangeID(Connection connection) throws EpochMismatchException {
		record Row(String epoch, Long revision){}
		var result = using(connection)
			.select(CHANGES.EPOCH, REVISION)
			.from(CHANGES)
			.where(REVISION.eq(select(max(REVISION)).from(CHANGES)))
			.fetchOneInto(Row.class);
		if (result == null) {
			// No changes yet; return something less than the first change ID
			return 0L; // TODO: Are we sure auto-increment always generates numbers strictly greater than zero?
		} else {
			if (!result.epoch.equals(this.epoch)) {
				throw new EpochMismatchException();
			}
			return result.revision;
		}
	}

	private static JavaType mapValueType(Class<?> entryType) {
		return typeFactory.constructParametricType(MapValue.class, entryType);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(SqlDriverImpl.class);
	private static final TypeFactory typeFactory = TypeFactory.createDefaultInstance();
}
