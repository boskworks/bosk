package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NonNull;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.codecs.BsonValueCodec;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskInfo;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.StateTreeSerializer;
import works.bosk.drivers.mongo.BsonSerializer;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static works.bosk.ReferenceUtils.rawClass;

/**
 * Facilities to translate between in-DB and in-memory representations.
 *
 * @author pdoyle
 */
final class Formatter extends BsonFormatter {

	/**
	 * If the diagnostic attributes are identical from one update to the next,
	 * MongoDB won't send them. This field retains the last value so we can
	 * always set the correct context for each downstream operation.
	 */
	private volatile MapValue<String> lastEventDiagnosticAttributes = MapValue.empty();
	private final Codec<?> manifestCodec = codecFor(Manifest.class);

	Formatter(BoskInfo<?> boskInfo, BsonSerializer bsonSerializer) {
		super(boskInfo, bsonSerializer);
	}

	/**
	 * Revision number zero represents a nonexistent version number,
	 * consistent with the behaviour of the MongoDB <code>$inc</code> operator,
	 * which treats a nonexistent field as a zero.
	 * For us, this occurs in two situations:
	 *
	 * <ol><li>
	 *     Initialization: There is no state information yet because the document doesn't exist
	 * </li><li>
	 *     Legacy: The database collection pre-dates revision numbers and doesn't have one
	 * </li></ol>
	 *
	 * The revision field in the database is never less than this value: it is initialized to
	 * {@link #REVISION_ONE} and incremented thereafter. Therefore, waiting for a revision
	 * greater than or equal to this is equivalent to waiting for any update at all.
	 */
	static final BsonInt64 REVISION_ZERO = new BsonInt64(0);

	/**
	 * The revision number used when the bosk document is first created.
	 */
	static final BsonInt64 REVISION_ONE = new BsonInt64(1);

	private final BsonInt32 SUPPORTED_MANIFEST_VERSION = new BsonInt32(1);

	//
	// Helpers to translate Bosk <-> MongoDB
	//

	@SuppressWarnings("unchecked")
	<T> T document2object(BsonDocument doc, Reference<T> target) {
		Type type = target.targetType();
		Class<T> objectClass = (Class<T>) rawClass(type);
		Codec<T> objectCodec = (Codec<T>) codecFor(type);
		try (@SuppressWarnings("unused") StateTreeSerializer.DeserializationScope scope = deserializationScopeFunction.apply(target)) {
			return objectClass.cast(objectCodec.decode(doc.asBsonReader(), DecoderContext.builder().build()));
		}
	}

	void validateManifest(BsonDocument manifest) throws UnrecognizedFormatException {
		try {
			Set<String> keys = new HashSet<>(manifest.keySet());
			List<String> supportedFormats = asList("sequoia", "pando");
			String detectedFormat = null;
			for (String format: supportedFormats) {
				if (keys.remove(format)) {
					if (detectedFormat == null) {
						detectedFormat = format;
					} else {
						throw new UnrecognizedFormatException("Found two supported formats: " + detectedFormat + " and " + format);
					}
				}
			}
			if (detectedFormat == null) {
				throw new UnrecognizedFormatException("Found none of the supported formats: " + supportedFormats);
			}
			HashSet<String> requiredKeys = new HashSet<>(singletonList("version"));
			if (!keys.equals(requiredKeys)) {
				keys.removeAll(requiredKeys);
				if (keys.isEmpty()) {
					requiredKeys.removeAll(manifest.keySet());
					throw new UnrecognizedFormatException("Missing keys in manifest: " + requiredKeys);
				} else {
					throw new UnrecognizedFormatException("Unrecognized keys in manifest: " + keys);
				}
			}
			if (!SUPPORTED_MANIFEST_VERSION.equals(manifest.getInt32("version"))) {
				throw new UnrecognizedFormatException("Manifest version " + manifest.getInt32("version") + " not suppoted");
			}
		} catch (ClassCastException e) {
			throw new UnrecognizedFormatException("Manifest field has unexpected type", e);
		}
	}

	Manifest decodeManifest(BsonDocument manifestDoc) throws UnrecognizedFormatException {
		BsonDocument manifest = manifestDoc.clone();
		manifest.remove("_id");
		validateManifest(manifest);
		return (Manifest) this.manifestCodec
			.decode(
				new BsonDocumentReader(manifest),
				DecoderContext.builder().build());
	}

	BsonDocument encodeDiagnostics(MapValue<String> attributes) {
		BsonDocument result = new BsonDocument();
		attributes.forEach((name, value) -> result.put(name, new BsonString(value)));
		return new BsonDocument("attributes", result);
	}

	MapValue<String> decodeDiagnosticAttributes(BsonDocument diagnostics) {
		MapValue<String> result = MapValue.empty();
		for (Map.Entry<String, BsonValue> foo: diagnostics.getDocument("attributes").entrySet()) {
			String name = foo.getKey();
			String value = foo.getValue().asString().getValue();
			result = result.with(name, value);
		}
		return result;
	}

	long bsonValueBinarySize(BsonValue bson) {
		Codec<BsonValue> codec = new BsonValueCodec();
		try (
			BasicOutputBuffer buffer = new BasicOutputBuffer();
			BsonBinaryWriter w = new BsonBinaryWriter(buffer)
		) {
			codec.encode(w, bson, EncoderContext.builder().build());
			return buffer.getPosition();
		}
	}

	BsonInt64 getRevisionFromFullDocument(BsonDocument fullDocument) {
		if (fullDocument == null) {
			return null;
		}
		return fullDocument.getInt64(DocumentFields.revision.name(), null);
	}

	@NonNull MapValue<String> eventDiagnosticAttributesFromFullDocument(BsonDocument fullDocument) {
		return getOrSetEventDiagnosticAttributes(getDiagnosticAttributesFromFullDocument(fullDocument));
	}

	MapValue<String> getDiagnosticAttributesFromFullDocument(BsonDocument fullDocument) {
		BsonDocument diagnostics = getDiagnosticAttributesIfAny(fullDocument);
		return diagnostics == null ? null : decodeDiagnosticAttributes(diagnostics);
	}

	BsonInt64 getRevisionFromUpdateEvent(ChangeStreamDocument<BsonDocument> event) {
		BsonDocument updatedFields = getUpdatedFieldsIfAny(event);
		if (updatedFields == null) {
			return null;
		}
		return updatedFields.getInt64(DocumentFields.revision.name(), null);
	}

	@NonNull MapValue<String> eventDiagnosticAttributesFromUpdate(ChangeStreamDocument<BsonDocument> event) {
		return getOrSetEventDiagnosticAttributes(getDiagnosticAttributesFromUpdateEvent(event));
	}

	MapValue<String> getDiagnosticAttributesFromUpdateEvent(ChangeStreamDocument<BsonDocument> event) {
		BsonDocument updatedFields = getUpdatedFieldsIfAny(event);
		BsonDocument diagnostics = getDiagnosticAttributesIfAny(updatedFields);
		return diagnostics == null ? null : decodeDiagnosticAttributes(diagnostics);
	}

	private static BsonDocument getUpdatedFieldsIfAny(ChangeStreamDocument<BsonDocument> event) {
		if (event == null) {
			return null;
		}
		UpdateDescription updateDescription = event.getUpdateDescription();
		if (updateDescription == null) {
			return null;
		}
		return updateDescription.getUpdatedFields();
	}

	static BsonDocument getDiagnosticAttributesIfAny(BsonDocument fullDocument) {
		if (fullDocument == null) {
			return null;
		}
		return fullDocument.getDocument(DocumentFields.diagnostics.name(), null);
	}

	@NonNull private MapValue<String> getOrSetEventDiagnosticAttributes(MapValue<String> fromEvent) {
		if (fromEvent == null) {
			LOGGER.debug("No diagnostic attributes in event; assuming they are unchanged");
			return lastEventDiagnosticAttributes;
		} else {
			lastEventDiagnosticAttributes = fromEvent;
			return fromEvent;
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(Formatter.class);
}
