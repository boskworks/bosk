package works.bosk.spring.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Reader;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import works.bosk.Bosk;
import works.bosk.Entity;
import works.bosk.EnumerableByIdentifier;
import works.bosk.Identifier;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.StateTreeSerializer;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NonexistentReferenceException;
import works.bosk.jackson.JacksonSerializer;

import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static works.bosk.spring.boot.WebProperties.DEFAULT_MAINTENANCE_PATH;

/**
 * HTTP {@code GET}, {@code PUT}, and {@code DELETE} access to the Bosk state tree as JSON.
 * <p>
 * These endpoints are registered only when {@code bosk.web.maintenance.access} selects an
 * access. In {@link MaintenanceAccess#AUTHENTICATED} mode, the application must authenticate
 * requests to the maintenance path; see {@link MaintenanceAccess}. A {@code PUT} or {@code DELETE}
 * is submitted through {@code bosk.driver()}, so it fires hooks and any downstream replication
 * or persistence.
 * <p>
 * A {@code GET} response for a node with a field named {@code revision} of type
 * {@link Identifier} includes a strong {@code ETag} derived from that revision. The value is
 * quoted and percent-encoded as needed to satisfy RFC 9110, so any revision string can be used.
 * {@code PUT} and {@code DELETE} accept the same value in an {@code If-Match} header.
 * Precondition mismatches are asynchronous no-ops: the endpoint still returns
 * {@code 202 Accepted}, and a client that needs to know whether the write applied must
 * {@code GET} the node again.
 */
@RestController
@RequestMapping("${bosk.web.maintenance.path:" + DEFAULT_MAINTENANCE_PATH + "}")
public class MaintenanceEndpoints {
	private final Bosk<?> bosk;
	private final ObjectMapper mapper;
	private final JacksonSerializer jackson;

	/**
	 * Whether each node type has a {@code revision} field of type {@link Identifier}.
	 * The maintenance endpoints are on a hot path, and {@link Reference#then} throws
	 * when the field is absent, so we determine the answer once per type and reuse it.
	 */
	private final ConcurrentMap<Class<?>, Boolean> revisionCapableTypes = new ConcurrentHashMap<>();

	MaintenanceEndpoints(
		Bosk<?> bosk,
		ObjectMapper mapper,
		JacksonSerializer jackson
	) {
		this.bosk = bosk;
		this.mapper = mapper;
		this.jackson = jackson;
	}

	@GetMapping(produces = APPLICATION_JSON_VALUE, path = {"", "{*path}"})
	Object getAny(
		@PathVariable(value="path", required = false) String path,
		HttpServletRequest req,
		HttpServletResponse rsp
	) {
		LOGGER.debug("{} {}", req.getMethod(), req.getRequestURI());
		Reference<?> ref = referenceForPath(path);
		try {
			Object value = ref.value();
			optionalRevisionRef(ref).ifPresent(revision ->
				rsp.setHeader(ETAG, Etag.headerValue(revision.value())));
			return value;
		} catch (NonexistentReferenceException e) {
			throw new ResponseStatusException(NOT_FOUND, "Object does not exist: " + ref, e);
		}
	}

	@PutMapping(consumes = APPLICATION_JSON_VALUE, path = {"", "{*path}"})
	<T> void putAny(
		@PathVariable(value = "path", required = false) String path,
		Reader body,
		HttpServletRequest req,
		HttpServletResponse rsp
	) throws IOException, InvalidTypeException {
		LOGGER.debug("{} {}", req.getMethod(), req.getRequestURI());
		@SuppressWarnings("unchecked")
		Reference<T> ref = (Reference<T>) referenceForPath(path);
		T newValue;
		try (@SuppressWarnings("unused") StateTreeSerializer.DeserializationScope scope = jackson.newDeserializationScope(ref)) {
			newValue = mapper
				.readerFor(mapper.constructType(ref.targetType()))
				.readValue(body);
		}
		checkForMismatchedID(ref, newValue);
		discriminatePreconditionCases(req, new PreconditionDiscriminator() {
			@Override
			public void ifUnconditional() {
				bosk.driver().submitReplacement(ref, newValue);
			}

			@Override
			public void ifMustMatch(Identifier expectedRevision) {
				bosk.driver().submitConditionalReplacement(
					ref, newValue,
					revisionRef(ref), expectedRevision);
			}

			@Override
			public void ifMustNotExist() {
				bosk.driver().submitConditionalCreation(ref, newValue);
			}
		});
		rsp.setStatus(ACCEPTED.value());
	}

	@DeleteMapping(path = {"", "{*path}"})
	void deleteAny(
		@PathVariable(value = "path", required = false) String path,
		HttpServletRequest req,
		HttpServletResponse rsp
	) {
		LOGGER.debug("{} {}", req.getMethod(), req.getRequestURI());
		Reference<?> ref = referenceForPath(path);
		discriminatePreconditionCases(req, new PreconditionDiscriminator() {
			@Override
			public void ifUnconditional() {
				bosk.driver().submitDeletion(ref);
			}

			@Override
			public void ifMustMatch(Identifier expectedRevision) {
				bosk.driver().submitConditionalDeletion(
					ref, revisionRef(ref), expectedRevision);
			}

			@Override
			public void ifMustNotExist() {
				// Request to delete a nonexistent object: nothing to do
			}
		});
		rsp.setStatus(ACCEPTED.value());
	}

	private Reference<?> referenceForPath(String path) {
		if (path == null) {
			return bosk.rootReference();
		}
		try {
			return bosk.rootReference().then(Object.class, Path.parse(path));
		} catch (InvalidTypeException e) {
			throw new ResponseStatusException(NOT_FOUND, "Invalid path: " + path, e);
		}
	}

	private Reference<Identifier> revisionRef(Reference<?> ref) {
		return optionalRevisionRef(ref).orElseThrow(() ->
			new ResponseStatusException(BAD_REQUEST, "Preconditions not supported for object with no suitable revision field: " + ref));
	}

	private Optional<Reference<Identifier>> optionalRevisionRef(Reference<?> ref) {
		if (revisionCapableTypes.computeIfAbsent(ref.targetClass(),
				_ -> ref.thenIfValid(Identifier.class, "revision").isPresent())) {
			return Optional.of(thenRevision(ref));
		} else {
			return Optional.empty();
		}
	}

	private Reference<Identifier> thenRevision(Reference<?> ref) {
		try {
			return ref.then(Identifier.class, "revision");
		} catch (InvalidTypeException e) {
			throw new AssertionError("Revision capability check passed but then() failed: " + ref, e);
		}
	}

	private interface PreconditionDiscriminator {
		void ifUnconditional();

		void ifMustMatch(Identifier expectedRevision);

		void ifMustNotExist();
	}

	/**
	 * ETags are a little fiddly to decode. This logic handles the various cases and error conditions,
	 * and then calls the given <code>discriminator</code> to perform the desired action.
	 * <p>
	 * As a convenience, if the discriminator throws {@link IllegalArgumentException},
	 * we'll wrap that in a {@link ResponseStatusException} using {@link HttpStatus#BAD_REQUEST}.
	 * {@link works.bosk.BoskDriver} throws {@link IllegalArgumentException} for invalid inputs,
	 * so this behaviour means the caller doesn't need to worry about this case.
	 */
	static void discriminatePreconditionCases(HttpServletRequest req, PreconditionDiscriminator discriminator) {
		String ifMatch = req.getHeader("If-Match");
		String ifNoneMatch = req.getHeader("If-None-Match");
		LOGGER.debug("| If-Match: {} -- If-None-Match: {}", ifMatch, ifNoneMatch);
		try {
			if (ifMatch == null) {
				if (ifNoneMatch == null) {
					LOGGER.trace("| Unconditional");
					discriminator.ifUnconditional();
				} else if ("*".equals(ifNoneMatch)) {
					LOGGER.trace("| MustNotExist");
					discriminator.ifMustNotExist();
				} else {
					throw new ResponseStatusException(BAD_REQUEST, "If-None-Match header, if supplied, must be \"*\"");
				}
			} else if (ifNoneMatch == null) {
				Identifier expectedRevision = Etag.revisionFrom(ifMatch);
				LOGGER.trace("| MustMatch({})", expectedRevision);
				discriminator.ifMustMatch(expectedRevision);
			} else {
				throw new ResponseStatusException(BAD_REQUEST, "Cannot supply both If-Match and If-None-Match");
			}
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
		}
	}

	private void checkForMismatchedID(Reference<?> ref, Object newValue) {
		if (newValue instanceof Entity e && !ref.path().isEmpty()) {
			Reference<?> enclosingRef = ref.enclosingReference(Object.class);
			if (EnumerableByIdentifier.class.isAssignableFrom(enclosingRef.targetClass())) {
				Identifier pathID = Identifier.from(ref.path().lastSegment());
				Identifier entityID = e.id();
				if (!pathID.equals(entityID)) {
					throw new ResponseStatusException(BAD_REQUEST, ref.getClass().getSimpleName() + " ID \"" + entityID + "\" does not match path ID \"" + pathID + "\"");
				}
			}
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceEndpoints.class);
}
