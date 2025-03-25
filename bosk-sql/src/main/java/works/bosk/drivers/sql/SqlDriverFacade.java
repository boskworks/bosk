package works.bosk.drivers.sql;

import java.io.IOException;
import java.lang.reflect.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.jackson.JacksonSerializer;

import static java.util.Objects.requireNonNull;

/**
 * The actual object a user interacts with.
 * Calls from users go through this object,
 * while internal calls can go through {@link SqlDriverImpl}
 * and skip certain logic that isn't needed for internal calls.
 */
class SqlDriverFacade implements SqlDriver {
	private final JacksonSerializer jacksonSerializer;
	final SqlDriverImpl impl;

	SqlDriverFacade(JacksonSerializer jacksonSerializer, SqlDriverImpl impl) {
		this.jacksonSerializer = requireNonNull(jacksonSerializer);
		this.impl = impl;
	}

	@Override
	public void close() {
		LOGGER.debug("close");
		impl.close();
	}

	@Override
	public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		LOGGER.debug("initialRoot({})", rootType);
		return impl.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		LOGGER.debug("submitReplacement({}, {})", target, newValue);
		jacksonSerializer.initializeAllEnclosingPolyfills(target, impl);
		impl.submitReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		LOGGER.debug("submitConditionalReplacement({}, {}, {}, {})", target, newValue, precondition, requiredValue);
		jacksonSerializer.initializeAllEnclosingPolyfills(target, impl);
		impl.submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		LOGGER.debug("submitConditionalCreation({}, {})", target, newValue);
		jacksonSerializer.initializeAllEnclosingPolyfills(target, impl);
		impl.submitConditionalCreation(target, newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		LOGGER.debug("submitDeletion({})", target);
		impl.submitDeletion(target);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		LOGGER.debug("submitConditionalDeletion({}, {}, {})", target, precondition, requiredValue);
		impl.submitConditionalDeletion(target, precondition, requiredValue);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		LOGGER.debug("flush");
		impl.flush();
	}

	/**
	 * We log under the auspices of {@link SqlDriver} since this object is
	 * the public facing object of that driver.
	 */
	@SuppressWarnings("LoggerInitializedWithForeignClass")
	private static final Logger LOGGER = LoggerFactory.getLogger(SqlDriver.class);
}
