package works.bosk.drivers.mongo.internal;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Tracks certain document fields across change stream events for a single collection.
 * <p>
 * MongoDB does not include unchanged fields in change stream events.
 * For fields that we need even when they haven't changed, we must keep track of them ourselves.
 */
@NullMarked
final class DocumentFieldTracker {
	private final ConcurrentHashMap<BsonValue, EnumMap<TrackedField, BsonValue>> fieldsByDocId = new ConcurrentHashMap<>();

	enum TrackedField {
		DIAGNOSTICS;

		final String fieldName;
		TrackedField() { this.fieldName = name().toLowerCase(Locale.ROOT); }
	}

	@Nullable BsonValue getField(BsonValue documentId, TrackedField field) {
		var fields = fieldsByDocId.get(documentId);
		return fields == null ? null : fields.get(field);
	}

	@Nullable BsonDocument getFieldAsDocument(BsonValue documentId, TrackedField field) {
		var fields = fieldsByDocId.get(documentId);
		if (fields == null) {
			return null;
		}
		BsonValue result = fields.get(field);
		if (result == null) {
			return null;
		}
		return result.asDocument();
	}

	/**
	 * Infers the latest values of tracked fields from a change stream event.
	 */
	public void processEvent(ChangeStreamDocument<BsonDocument> event) {
		if (event.getDocumentKey() == null) {
			return;
		}
		BsonValue rawId = event.getDocumentKey().get("_id");
		switch (event.getOperationType()) {
			case INSERT, REPLACE -> trackFields(rawId, event.getFullDocument());
			case UPDATE -> processUpdateEvent(rawId, event);
			case DELETE -> fieldsByDocId.remove(rawId);
			case DROP, DROP_DATABASE -> fieldsByDocId.clear();
			case null, default -> { /* OTHER, INVALIDATE — ignore */ }
		}
	}

	private void processUpdateEvent(BsonValue docId, ChangeStreamDocument<BsonDocument> event) {
		UpdateDescription updateDescription = event.getUpdateDescription();
		if (updateDescription != null) {
			trackFields(docId, updateDescription.getUpdatedFields());
			var toRemove = updateDescription.getRemovedFields();
			var fields = fieldsByDocId.get(docId);
			if (toRemove != null && fields != null) {
				toRemove.forEach(fieldName -> {
					for (var field : TrackedField.values()) {
						if (field.fieldName.equals(fieldName)) {
							fields.remove(field);
						}
					}
				});
			}
		}
	}

	private void trackFields(BsonValue docId, @Nullable BsonDocument fieldsDocument) {
		if (fieldsDocument == null) {
			return;
		}
		EnumMap<DocumentFieldTracker.TrackedField, BsonValue> fields = fieldsByDocId.computeIfAbsent(docId, _ -> new EnumMap<>(TrackedField.class));
		for (var field : TrackedField.values()) {
			BsonValue value = fieldsDocument.get(field.fieldName, null);
			if (value != null) {
				fields.put(field, value);
			}
		}
	}
}
