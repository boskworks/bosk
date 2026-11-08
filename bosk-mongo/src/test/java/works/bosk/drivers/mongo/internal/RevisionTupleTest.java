package works.bosk.drivers.mongo.internal;

import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonInvalidOperationException;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import works.bosk.drivers.mongo.internal.BsonFormatter.DocumentFields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevisionTupleTest {

	@Test
	void sameIncarnationAs_comparesIdentities() {
		assertTrue(new RevisionTuple("incarnation", 2).sameIncarnationAs(new RevisionTuple("incarnation", 1)),
			"Revisions from the same incarnation match");
		assertFalse(new RevisionTuple("incarnation", 2).sameIncarnationAs(new RevisionTuple("other", 2)),
			"Revisions from different incarnations do not match");
	}

	@Test
	void newLifetime_hasFreshIncarnationAndRevisionOne() {
		RevisionTuple first = RevisionTuple.newLifetime();
		RevisionTuple second = RevisionTuple.newLifetime();
		assertNotEquals(first.incarnation(), second.incarnation(),
			"Each lifetime must have its own incarnation");
		assertEquals(1, first.revision());
		assertEquals(1, second.revision());
	}

	@Test
	void nextAfter_initializesOrIncrements() {
		RevisionTuple first = RevisionTuple.nextAfter(null);
		assertFalse(first.incarnation().isEmpty(), "A new document must have an incarnation");
		assertEquals(1, first.revision());

		RevisionTuple next = RevisionTuple.nextAfter(new RevisionTuple("incarnation", 7));
		assertEquals("incarnation", next.incarnation());
		assertEquals(8, next.revision());
	}

	@Test
	void from_readsRevisionAndIncarnation() {
		BsonDocument document = new BsonDocument()
			.append(DocumentFields.incarnation.name(), new BsonString("incarnation"))
			.append(DocumentFields.revision.name(), new BsonInt64(7));

		assertEquals(new RevisionTuple("incarnation", 7), RevisionTuple.from(document));
	}

	@Test
	void from_missingFields_throws() {
		assertThrows(BsonInvalidOperationException.class,
			() -> RevisionTuple.from(new BsonDocument()));
		assertThrows(BsonInvalidOperationException.class,
			() -> RevisionTuple.from(new BsonDocument(
				DocumentFields.incarnation.name(), new BsonString("incarnation"))));
		assertThrows(BsonInvalidOperationException.class,
			() -> RevisionTuple.from(new BsonDocument(
				DocumentFields.revision.name(), new BsonInt64(1))));
	}

	@Test
	void putInto_writesBothFields() {
		BsonDocument document = new BsonDocument();
		new RevisionTuple("incarnation", 7).putInto(document);

		assertEquals(new RevisionTuple("incarnation", 7), RevisionTuple.from(document));
	}
}
