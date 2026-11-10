package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import com.mongodb.MongoOperationTimeoutException;
import com.mongodb.TransactionOptions;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.exceptions.RetryableUpdateException;

import static com.mongodb.MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Executes an update operation in the kind of session the format driver requires.
 * <p>
 * This is an internal helper that owns the session and transaction lifecycle and
 * translates database failures into the driver's exception types. It does not
 * own reconnection, retries, polyfill initialization, or state loading; those
 * remain {@link MainDriver}'s job.
 * <p>
 * Each instance is bound to a single {@link FormatDriver}. When
 * {@link MainDriver} replaces its format driver, it replaces this executor too.
 */
final class UpdateDriver {
	private final FormatDriver<?> formatDriver;
	private final SessionCollection collection;
	private final TransactionOptions transactionOptions;
	private final Consumer<Throwable> onFailure;
	private final Duration conflictRetryAfter;

	/**
	 * @param formatDriver the format driver that plans this executor's updates
	 * @param collection the session-bound collection used to reach the database
	 * @param timescaleMS the driver's timescale, used as the transaction timeout
	 * and as the retry hint when write conflicts exhaust that timeout
	 * @param onFailure called when the driver must be disconnected; the
	 * {@link MainDriver} uses this to stop submitting updates until it has
	 * re-established a valid understanding of the database
	 */
	UpdateDriver(FormatDriver<?> formatDriver, SessionCollection collection, long timescaleMS, Consumer<Throwable> onFailure) {
		this.formatDriver = formatDriver;
		this.collection = collection;
		this.transactionOptions = TransactionOptions.builder()
			.timeout(timescaleMS, MILLISECONDS)
			.build();
		this.onFailure = onFailure;
		this.conflictRetryAfter = Duration.ofMillis(timescaleMS);
	}

	/**
	 * Runs {@code operation}, using a retryable-write session or a
	 * client-library transaction according to the format driver.
	 */
	void execute(Runnable operation) {
		if (formatDriver.updatesAreTransactional()) {
			runInClientLibraryTransaction(operation);
		} else {
			runWithSession(operation);
		}
	}

	private void runWithSession(Runnable operation) {
		try (var _ = collection.newSession()) {
			operation.run();
		} catch (FailedMongoClientSessionException e) {
			throw disconnect(e);
		}
	}

	/**
	 * Runs {@code operation} in a transaction owned by MongoDB's client library,
	 * which starts the transaction, retries the body on transient errors, and
	 * commits it.
	 */
	private void runInClientLibraryTransaction(Runnable operation) {
		try (var _ = collection.newSession()) {
			collection.withTransaction(transactionOptions, () -> {
				operation.run();
				return null;
			});
		} catch (FailedMongoClientSessionException e) {
			throw disconnect(e);
		} catch (MongoException e) {
			throw transactionFailure(e);
		}
	}

	/**
	 * Chooses the exception that an update should throw for a failed
	 * transaction. Write-conflict exhaustion is retryable and leaves the driver
	 * connected; everything else is a database-health failure that disconnects.
	 */
	private RuntimeException transactionFailure(MongoException e) {
		if (isWriteConflict(e)) {
			// The transaction kept losing to other writers. That's contention,
			// not a database-health problem, so leave the driver connected and
			// let the caller retry later.
			LOGGER.warn("Exhausted transaction conflict retries after {} ms", conflictRetryAfter.toMillis(), e);
			return new RetryableUpdateException(
				"Unable to complete update after transaction conflict retries",
				e, conflictRetryAfter);
		} else {
			LOGGER.debug("MongoException is not recoverable; disconnecting", e);
			return disconnect(e);
		}
	}

	/**
	 * Whether {@code e} is the client library giving up on a transaction because
	 * it kept losing write conflicts to other writers.
	 * <p>
	 * The client library's {@code withTransaction} puts the
	 * transient-transaction-error label on connectivity failures as well as on
	 * write conflicts, but a connectivity failure means the change receiver is
	 * probably broken and must still take the disconnect path. Only a genuine
	 * write conflict is safe to report as a retryable update.
	 * <p>
	 * The library may also wrap the final write conflict in a timeout exception
	 * when the transaction budget expires, which lacks the label, so accept that
	 * form as well.
	 */
	private static boolean isWriteConflict(MongoException e) {
		if (e.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR_LABEL) || e instanceof MongoOperationTimeoutException) {
			for (Throwable t = e; t != null; t = t.getCause()) {
				if (t instanceof MongoException m && m.getCode() == WRITE_CONFLICT_CODE) {
					return true;
				}
			}
		}
		return false;
	}

	private DisconnectedException disconnect(Throwable reason) {
		onFailure.accept(reason);
		return new DisconnectedException(reason);
	}

	/**
	 * The server's {@code WriteConflict} error code, from
	 * <a href="https://www.mongodb.com/docs/manual/reference/error-codes/">MongoDB's error codes table</a>.
	 * The client library has no constant for it. We need the code as well as the
	 * transient-transaction-error label because the library attaches that label
	 * to connectivity failures too.
	 */
	private static final int WRITE_CONFLICT_CODE = 112;
	private static final Logger LOGGER = LoggerFactory.getLogger(UpdateDriver.class);
}
