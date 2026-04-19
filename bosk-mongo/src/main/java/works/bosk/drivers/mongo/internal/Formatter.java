package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonBinaryWriter;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.codecs.BsonValueCodec;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskContext.Tenant;
import works.bosk.BoskInfo;
import works.bosk.Identifier;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.StateTreeSerializer;
import works.bosk.drivers.mongo.BsonSerializer;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static works.bosk.ReferenceUtils.rawClass;

/**
 * Facilities to translate between in-DB and in-memory representations.
 *
 * @author pdoyle
 */
final class Formatter extends BsonFormatter {

	private volatile Tenant.Established lastEventTenant = null;

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

	BsonValue encodeMaybeTenant(Tenant tenant) {
		return switch(tenant) {
			case Tenant.NotEstablished _ -> BsonNull.VALUE;
			case Tenant.Established t -> encodeTenant(t);
		};
	}

	BsonValue encodeTenant(Tenant.Established tenant) {
		return switch(tenant) {
			case Tenant.None _ -> new BsonBoolean(false);
			case Tenant.SetTo(var id) -> new BsonString(id.toString());
		};
	}

	Tenant.Established decodeTenant(BsonValue tenant) {
		return switch (tenant) {
			case BsonNull _ -> Tenant.NONE;
			case BsonBoolean b -> {
				if (b.getValue()) {
					throw new IllegalArgumentException("Unexpected tenant value: " + tenant);
				} else {
					yield Tenant.NONE;
				}
			}
			case BsonString s -> new Tenant.SetTo(Identifier.from(s.getValue()));
			default -> throw new IllegalArgumentException("Unexpected tenant value: " + tenant);
		};
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

	@Nonnull Tenant.Established eventTenantFromFullDocument(BsonDocument fullDocument) {
		return getOrSetEventTenant(getTenantFromFullDocument(fullDocument));
	}

	@Nonnull MapValue<String> eventDiagnosticAttributesFromFullDocument(BsonDocument fullDocument) {
		return getOrSetEventDiagnosticAttributes(getDiagnosticAttributesFromFullDocument(fullDocument));
	}

	Tenant.Established getTenantFromFullDocument(BsonDocument fullDocument) {
		BsonValue tenant = getTenantIfAny(fullDocument);
		return tenant == null ? null : decodeTenant(tenant);
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

	@Nonnull Tenant.Established eventTenantFromUpdate(ChangeStreamDocument<BsonDocument> event) {
		return getOrSetEventTenant(getTenantFromUpdateEvent(event));
	}

	@Nonnull MapValue<String> eventDiagnosticAttributesFromUpdate(ChangeStreamDocument<BsonDocument> event) {
		return getOrSetEventDiagnosticAttributes(getDiagnosticAttributesFromUpdateEvent(event));
	}

	Tenant.Established getTenantFromUpdateEvent(ChangeStreamDocument<BsonDocument> event) {
		BsonDocument updatedFields = getUpdatedFieldsIfAny(event);
		BsonValue tenant = getTenantIfAny(updatedFields);
		return tenant == null ? null : decodeTenant(tenant);
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

	static BsonValue getTenantIfAny(BsonDocument fullDocument) {
		if (fullDocument == null) {
			return null;
		}
		return fullDocument.get(DocumentFields.tenant.name(), null);
	}

	static BsonDocument getDiagnosticAttributesIfAny(BsonDocument fullDocument) {
		if (fullDocument == null) {
			return null;
		}
		return fullDocument.getDocument(DocumentFields.diagnostics.name(), null);
	}

	@Nonnull private MapValue<String> getOrSetEventDiagnosticAttributes(MapValue<String> fromEvent) {
		if (fromEvent == null) {
			LOGGER.debug("No diagnostic attributes in event; assuming they are unchanged");
			return lastEventDiagnosticAttributes;
		} else {
			lastEventDiagnosticAttributes = fromEvent;
			return fromEvent;
		}
	}

	@Nonnull private Tenant.Established getOrSetEventTenant(@Nullable Tenant.Established fromEvent) {
		if (fromEvent == null) {
			LOGGER.debug("No tenant info in event; assuming unchanged: {}", lastEventTenant);
			return requireNonNull(lastEventTenant,
				"If event has no tenant info, we must have it from a prior event");
		} else {
			LOGGER.debug("Saving tenant info from event: {}", fromEvent);
			lastEventTenant = fromEvent;
			return fromEvent;
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(Formatter.class);
}
