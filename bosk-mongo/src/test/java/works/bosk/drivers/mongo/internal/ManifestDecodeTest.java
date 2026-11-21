package works.bosk.drivers.mongo.internal;

import java.io.IOException;
import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Identifier;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * The manifest is a format descriptor, so a manifest whose contents the codec
 * cannot read is an unrecognized format rather than an arbitrary failure.
 */
class ManifestDecodeTest {
	private Formatter formatter;

	@BeforeEach
	void setup() throws InvalidTypeException, IOException, InterruptedException {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName("Manifest"),
			TestEntity.class,
			AbstractMongoDriverTest::initialRoot,
			BoskConfig.<TestEntity>builder().build());
		formatter = new Formatter(bosk, new BsonSerializer());
	}

	@Test
	void manifestWithoutGraftPoints_isUnrecognized() {
		BsonDocument document = encodedManifest();
		document.getDocument("pando").remove("graftPoints");

		UnrecognizedFormatException e = assertThrows(UnrecognizedFormatException.class,
			() -> formatter.decodeManifest(document));

		assertEquals("Manifest contents are invalid", e.getMessage());
		assertTrue(e.getCause().getMessage().contains("graftPoints"),
			"The cause should name the missing field: " + e.getCause().getMessage());
	}

	private BsonDocument encodedManifest() {
		Manifest manifest = Manifest.forPando(
			PandoFormat.withGraftPoints("/catalog"),
			Identifier.from("epoch"));
		return (BsonDocument) formatter.object2bsonValue(manifest, Manifest.class);
	}

}
