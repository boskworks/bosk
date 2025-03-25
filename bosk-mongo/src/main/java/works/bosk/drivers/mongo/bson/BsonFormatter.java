package works.bosk.drivers.mongo.bson;

import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWriter;
import org.bson.BsonReader;
import org.bson.BsonValue;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodecProvider;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import works.bosk.BoskDiagnosticContext;
import works.bosk.BoskInfo;
import works.bosk.Listing;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.StateTreeSerializer;
import works.bosk.SideTable;
import works.bosk.exceptions.InvalidTypeException;

import static works.bosk.Path.validSegment;
import static works.bosk.ReferenceUtils.rawClass;

/**
 * Utility class for encoding and decoding bosk state trees as BSON.
 */
public class BsonFormatter {
	protected static final UnaryOperator<String> DECODER;
	protected static final UnaryOperator<String> ENCODER;
	protected final CodecRegistry simpleCodecs;
	protected final Function<Type, Codec<?>> preferredBoskCodecs;
	protected final Function<Reference<?>, StateTreeSerializer.DeserializationScope> deserializationScopeFunction;

	public BsonFormatter(BoskInfo<?> boskInfo, BsonSerializer bsonSerializer) {
		this.simpleCodecs = CodecRegistries.fromProviders(
			bsonSerializer.codecProviderFor(boskInfo),
			new ValueCodecProvider(),
			new DocumentCodecProvider());
		this.preferredBoskCodecs = type -> bsonSerializer.getCodec(type, rawClass(type), simpleCodecs, boskInfo);
		this.deserializationScopeFunction = bsonSerializer::newDeserializationScope;
	}

	public static String dottedFieldNameSegment(String segment) {
		return ENCODER.apply(validSegment(segment));
	}

	public static String undottedFieldNameSegment(String dottedSegment) {
		return DECODER.apply(dottedSegment);
	}

	/**
	 * @param refLength behave as though <code>ref</code> were truncated to this length without actually having to do it
	 */
	private static <T> void buildDottedFieldNameOf(Reference<T> ref, int startLength, int refLength, ArrayList<String> segments) {
		if (ref.path().length() > startLength) {
			Reference<?> enclosingReference = ref.enclosingReference(Object.class);
			BsonFormatter.buildDottedFieldNameOf(enclosingReference, startLength, refLength, segments);
			if (ref.path().length() <= refLength) {
				if (Listing.class.isAssignableFrom(enclosingReference.targetClass())) {
					segments.add("ids");
				} else if (SideTable.class.isAssignableFrom(enclosingReference.targetClass())) {
					segments.add("valuesById");
				}
				segments.add(BsonFormatter.dottedFieldNameSegment(ref.path().lastSegment()));
			}
		}
	}

	static {
		DECODER = s->{
			return URLDecoder.decode(s, StandardCharsets.UTF_8);
		};

		ENCODER = s->{
			// Selective percent-encoding of characters MongoDB doesn't like.
			// Standard percent-encoding doesn't handle the period character, which
			// we want, so if we're already diverging from the standard, we might
			// as well do something that suits our needs.
			// Good to stay compatible with standard percent-DEcoding, though.
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < s.length(); ) {
				int cp = s.codePointAt(i);
				switch (cp) {
					case '%': // For percent-encoding
					case '+': case ' ': // These two are affected by URLDecoder
					case '$': // MongoDB treats these specially
					case '.': // MongoDB separator for dotted field names
					case 0:   // Can MongoDB handle nulls? Probably. Do we want to find out? Not really.
					case '|': // (These are reserved for internal use)
					case '!':
					case '~':
					case '[':
					case ']':
						appendPercentEncoded(sb, cp);
						break;
					default:
						sb.appendCodePoint(cp);
						break;
				}
				i += Character.charCount(cp);
			}
			return sb.toString();
		};
	}

	private static void appendPercentEncoded(StringBuilder sb, int cp) {
		assert 0 <= cp && cp <= 255;
		sb
			.append('%')
			.append(hexCharForDigit(cp / 16))
			.append(hexCharForDigit(cp % 16));
	}

	/**
	 * An uppercase version of {@link Character#forDigit} with a radix of 16.
	 */
	private static char hexCharForDigit(int value) {
		if (value < 10) {
			return (char)('0' + value);
		} else {
			return (char)('A' + value - 10);
		}
	}

	/**
	 * @param ref the bosk node whose field name is required
	 * @param startingRef the bosk node corresponding to the MongoDB document in which {@code ref} is located
	 * @return MongoDB field name corresponding to {@code ref} within the document for {@code startingRef}, starting with {@link DocumentFields#state state}
	 * @see #referenceTo(String, Reference)
	 */
	public static <T> String dottedFieldNameOf(Reference<T> ref, Reference<?> startingRef) {
		assert startingRef.encloses(ref);
		return dottedFieldNameOf(ref, ref.path().length(), startingRef);
	}

	/**
	 * @param refLength behave as though <code>ref</code> were truncated to this length, without actually having to do it
	 * @return MongoDB field name corresponding to the given Reference
	 * @see #referenceTo(String, Reference)
	 */
	static <T> String dottedFieldNameOf(Reference<T> ref, int refLength, Reference<?> startingRef) {
		// TODO: Is this required? It's currently only really called by tests
		ArrayList<String> segments = dottedFieldNameSegments(ref, refLength, startingRef);
		return String.join(".", segments.toArray(new String[0]));
	}

	/**
	 * @return Reference corresponding to the given field name within a document representing {@code startingReference}
	 * @see #dottedFieldNameOf
	 */
	@SuppressWarnings("unchecked")
	public static <T> Reference<T> referenceTo(String dottedName, Reference<?> startingReference) throws InvalidTypeException {
		Reference<?> ref = startingReference;
		Iterator<String> iter = Arrays.asList(dottedName.split(Pattern.quote("."))).iterator();
		BsonFormatter.skipField(ref, iter, DocumentFields.state.name()); // The entire Bosk state is in this field
		while (iter.hasNext()) {
			if (Listing.class.isAssignableFrom(ref.targetClass())) {
				BsonFormatter.skipField(ref, iter, "ids");
			} else if (SideTable.class.isAssignableFrom(ref.targetClass())) {
				BsonFormatter.skipField(ref, iter, "valuesById");
			}
			if (iter.hasNext()) {
				String segment = undottedFieldNameSegment(iter.next());
				ref = ref.then(Object.class, segment);
			}
		}
		return (Reference<T>) ref;
	}

	private static void skipField(Reference<?> ref, Iterator<String> iter, String expectedName) {
		String actualName;
		try {
			actualName = iter.next();
		} catch (NoSuchElementException e) {
			throw new IllegalStateException("Expected '" + expectedName + "' for " + ref.targetClass().getSimpleName() + "; encountered end of dotted field name");
		}
		if (!expectedName.equals(actualName)) {
			throw new IllegalStateException("Expected '" + expectedName + "' for " + ref.targetClass().getSimpleName() + "; was: " + actualName);
		}
	}

	/**
	 * A <em>BSON path</em> is a pipe-delimited sequence of BSON fields.
	 *
	 * <p>
	 * Note that the BSON structure is such that the BSON path's segments may
	 * not exactly match those of {@code ref.path()}.
	 *
	 * <p>
	 * This computes the BSON path for {@code ref} within a document representing {@code startingRef}.
	 *
	 * @param ref reference to the node whose path is desired
	 * @param startingRef reference to the node corresponding to the document containing the {@code ref} node
	 * @return the BSON path leading to {@code ref} within a document representing {@code startingRef}
	 */
	public static String docBsonPath(Reference<?> ref, Reference<?> startingRef) {
		return "|" + String.join("|", docSegments(ref, startingRef));
	}

	/**
	 * @return list of field names suitable for {@link #lookup} to find the document corresponding
	 * to <code>docRef</code> inside a document corresponding to <code>rootRef</code>
	 */
	private static List<String> docSegments(Reference<?> docRef, Reference<?> rootRef) {
		ArrayList<String> allSegments = dottedFieldNameSegments(docRef, docRef.path().length(), rootRef);
		return allSegments
			.subList(1, allSegments.size()); // Skip the "state" field
	}

	protected Codec<?> codecFor(Type type) {
		// BsonSerializer gives better codecs than CodecRegistry, because BsonSerializer is aware of generics,
		// so we always try that first. The CodecSupplier protocol uses "null" to indicate that another
		// CodecSupplier should be used, so we follow that protocol and fall back on the CodecRegistry.
		Codec<?> result = preferredBoskCodecs.apply(type);
		if (result == null) {
			return simpleCodecs.get(rawClass(type));
		} else {
			return result;
		}
	}

	/**
	 * Converts a bosk state node to BSON.
	 *
	 * <p>
	 * A common way to call this is, for a given {@link Reference} {@code ref}, is:
	 * <pre>
	 *     ref.value(), ref.targetType()
	 * </pre>
	 * @param object the bosk state node to convert
	 * @param type the type of {@code object}
	 * @see #bsonValue2object(BsonValue, Reference)
	 */
	@SuppressWarnings("unchecked")
	public <T> BsonValue object2bsonValue(T object, Type type) {
		rawClass(type).cast(object);
		Codec<T> objectCodec = (Codec<T>) codecFor(type);
		BsonDocument document = new BsonDocument();
		try (BsonDocumentWriter writer = new BsonDocumentWriter(document)) {
			// To support arbitrary values, not just whole documents, we put the result INSIDE a document.
			writer.writeStartDocument();
			writer.writeName("value");
			objectCodec.encode(writer, object, EncoderContext.builder().build());
			writer.writeEndDocument();
		}
		return document.get("value");
	}

	/**
	 * Converts a BSON value to a bosk state node.
	 * @param bson the value to convert
	 * @param target the bosk location of the resulting node
	 * @see #object2bsonValue(Object, Type)
	 */
	@SuppressWarnings("unchecked")
	public <T> T bsonValue2object(BsonValue bson, Reference<T> target) {
		Codec<T> objectCodec = (Codec<T>) codecFor(target.targetType());
		BsonDocument document = new BsonDocument();
		document.append("value", bson);
		try (
			@SuppressWarnings("unused") StateTreeSerializer.DeserializationScope scope = deserializationScopeFunction.apply(target);
			BsonReader reader = document.asBsonReader()
		) {
			reader.readStartDocument();
			reader.readName("value");
			return objectCodec.decode(reader, DecoderContext.builder().build());
		}
	}

	/**
	 * The fields of the main MongoDB document.  Case-sensitive.
	 *
	 * <p>
	 * No field name should be a prefix of any other.
	 */
	public enum DocumentFields {
		/**
		 * The location of this document's state within the conceptual giant document.
		 * A pipe-separated list of BSON field names.
		 *
		 * <p>
		 * We put the whole path in _id so that it will be present in change stream documents.
		 */
		_id,

		/**
		 * The {@link Path#urlEncoded() url encoded} {@link Path path} of the location of
		 * this document's state within the bosk tree.
		 */
		path,

		/**
		 * The BSON-encoded portion of the bosk state represented by this document.
		 */
		state,

		/**
		 * An ever-increasing 64-bit long that is incremented every time the document changes.
		 */
		revision,

		/**
		 * The contents of {@link BoskDiagnosticContext} corresponding to this
		 * document's last update.
		 */
		diagnostics,
	}

	static <T> ArrayList<String> dottedFieldNameSegments(Reference<T> ref, int refLength, Reference<?> startingRef) {
		assert startingRef.path().matchesPrefixOf(ref.path()): "'" + ref + "' must be under '" + startingRef + "'";
		ArrayList<String> segments = new ArrayList<>();
		segments.add(DocumentFields.state.name());
		buildDottedFieldNameOf(ref, startingRef.path().length(), refLength, segments);
		return segments;
	}

	/**
	 * @param elementRefLength behave as though <code>elementRef</code> were truncated to this length, without actually having to do it
	 * @return MongoDB field name corresponding to the object that contains the given element
	 * @see #referenceTo(String, Reference)
	 */
	static <T> List<String> containerSegments(Reference<T> elementRef, int elementRefLength, Reference<?> startingRef) {
		List<String> elementSegments = dottedFieldNameSegments(elementRef, elementRefLength, startingRef);
		return elementSegments.subList(0, elementSegments.size()-1); // Trim off the element itself
	}

}
