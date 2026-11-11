package works.bosk.spring.boot;

import jakarta.servlet.ServletContext;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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

import static org.springframework.http.HttpHeaders.IF_NONE_MATCH;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.NOT_MODIFIED;
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
 * quoted and percent-encoded using the same scheme as path segments, so any revision string
 * can be used.
 * A {@code GET} whose {@code If-None-Match} header matches that {@code ETag} returns
 * {@code 304 Not Modified} with no body. The header may list several tags, or use the weak form
 * of a tag, and {@code *} may be read as a list of every possible tag: it matches exactly when
 * the node has a {@code revision} field. A node without one never has an {@code ETag}, so no
 * {@code If-None-Match} value can match it and its {@code GET} always returns {@code 200}.
 * {@code If-Match} is not evaluated on {@code GET} or {@code HEAD}.
 * {@code PUT} and {@code DELETE} accept the same value in an {@code If-Match} header.
 * Precondition mismatches are asynchronous no-ops: the endpoint still returns
 * {@code 202 Accepted}, and a client that needs to know whether the write applied must
 * {@code GET} the node again.
 * <p>
 * A {@code GET} of {@code openapi.json} beneath the maintenance path returns an OpenAPI 3.1
 * document describing the endpoints and the state tree's JSON representation.
 * The document is generated from the state tree's types and is covered by the same
 * authentication rules as the endpoints themselves.
 */
@RestController
@RequestMapping("${bosk.web.maintenance.path:" + DEFAULT_MAINTENANCE_PATH + "}")
public class MaintenanceEndpoints {
	private final Bosk<?> bosk;
	private final ObjectMapper mapper;
	private final JacksonSerializer jackson;
	private final String maintenancePath;
	private final ObjectNode openApiDocument;

	/**
	 * Whether each node type has a {@code revision} field of type {@link Identifier}.
	 * This depends only on the type, not on the path, so we ask once per type and reuse
	 * the answer. That keeps the common case for nodes without such a field off the
	 * {@link InvalidTypeException} path.
	 */
	private final ConcurrentMap<Class<?>, Boolean> typesWithRevision = new ConcurrentHashMap<>();

	MaintenanceEndpoints(
		Bosk<?> bosk,
		ObjectMapper mapper,
		JacksonSerializer jackson,
		WebProperties properties,
		ServletContext servletContext
	) {
		this.bosk = bosk;
		this.mapper = mapper;
		this.jackson = jackson;
		this.maintenancePath = properties.maintenance().path();
		this.openApiDocument = BoskOpenApi.documentFor(bosk, maintenancePath, servletContext.getContextPath());
	}

	@GetMapping(produces = APPLICATION_JSON_VALUE, path = {"", "{*path}"})
	ResponseEntity<Object> getAny(
		@PathVariable(value="path", required = false) String path,
		HttpServletRequest req
	) {
		LOGGER.debug("{} {}", req.getMethod(), req.getRequestURI());
		Reference<?> ref = referenceForPath(path);
		try {
			Object value = ref.value();
			Optional<Identifier> revision = optionalRevisionRef(ref).map(Reference::value);
			if (revision.isPresent() && isWildcard(req.getHeader(IF_NONE_MATCH))) {
				// "*" lists every possible ETag, so it matches whenever the node has one.
				return ResponseEntity.status(NOT_MODIFIED)
					.eTag(Etag.headerValue(revision.get()))
					.build();
			}
			// Spring MVC evaluates the remaining If-None-Match forms (tags, lists, weak tags)
			// against this ETag and suppresses the body with a 304 when one matches.
			ResponseEntity.BodyBuilder response = ResponseEntity.ok();
			revision.ifPresent(r -> response.eTag(Etag.headerValue(r)));
			return response.body(value);
		} catch (NonexistentReferenceException e) {
			throw new ResponseStatusException(NOT_FOUND, "Object does not exist: " + ref, e);
		}
	}

	/**
	 * Returns an OpenAPI 3.1 description of the maintenance endpoints for this Bosk.
	 * <p>
	 * The document is generated from the state tree's types at initialization.
	 * It is served beneath the maintenance path so that the application's security rules
	 * cover it along with the endpoints it describes.
	 */
	@GetMapping(produces = APPLICATION_JSON_VALUE, path = "openapi.json")
	ObjectNode getOpenApi(HttpServletRequest req) {
		LOGGER.debug("{} {}", req.getMethod(), req.getRequestURI());
		return openApiDocument;
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

	private static boolean isWildcard(String ifNoneMatch) {
		return ifNoneMatch != null && "*".equals(ifNoneMatch.trim());
	}

	private Reference<Identifier> revisionRef(Reference<?> ref) {
		return optionalRevisionRef(ref).orElseThrow(() ->
			new ResponseStatusException(BAD_REQUEST, "Preconditions not supported for object with no suitable revision field: " + ref));
	}

	private Optional<Reference<Identifier>> optionalRevisionRef(Reference<?> ref) {
		// The result depends only on the target type, so probe each type once.
		if (typesWithRevision.computeIfAbsent(ref.targetClass(),
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
			throw new AssertionError("thenIfValid() succeeded but then() failed: " + ref, e);
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
