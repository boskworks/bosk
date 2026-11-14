package works.bosk.drivers.mongo.internal;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.codecs.BsonValueCodec;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import works.bosk.BoskInfo;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.StateTreeSerializer;
import works.bosk.drivers.mongo.BsonSerializer;

import static java.util.Arrays.asList;
import static works.bosk.ReferenceUtils.rawClass;

/**
 * Facilities to translate between in-DB and in-memory representations.
 *
 * @author pdoyle
 */
final class Formatter extends BsonFormatter {

	private final Codec<?> manifestCodec = codecFor(Manifest.class);

	Formatter(BoskInfo<?> boskInfo, BsonSerializer bsonSerializer) {
		super(boskInfo, bsonSerializer);
	}

	private static final Set<BsonInt32> SUPPORTED_MANIFEST_VERSIONS = Set.of(new BsonInt32(1));

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
			HashSet<String> requiredKeys = new HashSet<>(asList("version", "formatId"));
			if (!keys.equals(requiredKeys)) {
				keys.removeAll(requiredKeys);
				if (keys.isEmpty()) {
					requiredKeys.removeAll(manifest.keySet());
					throw new UnrecognizedFormatException("Missing keys in manifest: " + requiredKeys);
				} else {
					throw new UnrecognizedFormatException("Unrecognized keys in manifest: " + keys);
				}
			}
			if (!SUPPORTED_MANIFEST_VERSIONS.contains(manifest.getInt32("version"))) {
				throw new UnrecognizedFormatException("Manifest version " + manifest.getInt32("version") + " not supported");
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

	Optional<BsonString> epochOf(BsonDocument fullDocument) {
		if (fullDocument == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(fullDocument.getString(DocumentFields.epoch.name(), null));
	}

}
