package works.bosk.drivers.mongo.bson;

import java.io.IOException;
import java.util.ArrayList;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.AbstractBoskTest;
import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.TestEntityBuilder;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.util.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BsonFormatterTest extends AbstractBoskTest {
	Bosk<TestRoot> bosk;
	CatalogReference<TestEntity> entitiesRef;
	Reference<TestEntity> weirdRef;
	static final String WEIRD_ID = "weird|i.d.";
	BsonFormatter formatter;
	private TestEntity weirdEntity;

	@BeforeEach
	void setupFormatter() throws InvalidTypeException, IOException, InterruptedException {
		bosk = setUpBosk(Bosk.simpleDriver());
		TestEntityBuilder builder = new TestEntityBuilder(bosk);
		entitiesRef = builder.entitiesRef();
		weirdRef = builder.entityRef(Identifier.from(WEIRD_ID));
		weirdEntity = builder.blankEntity(Identifier.from(WEIRD_ID), TestEnum.OK);
		bosk.driver().submitReplacement(entitiesRef, Catalog.of(weirdEntity));
		bosk.driver().flush();
		formatter = new BsonFormatter(bosk, new BsonSerializer());
	}

	@Test
	void object2bsonValue() {
		BsonValue actual = formatter.object2bsonValue(Catalog.of(weirdEntity), Types.parameterizedType(Catalog.class, TestEntity.class));
		BsonValue weirdDoc = new BsonDocument()
			.append("id", new BsonString(WEIRD_ID))
			.append("string", new BsonString(WEIRD_ID))
			.append("testEnum", new BsonString("OK"))
			.append("children", new BsonDocument())
			.append("oddChildren", new BsonDocument()
				.append("domain", new BsonString("/entities/weird%7Ci.d./children"))
				.append("ids", new BsonDocument())
			)
			.append("stringSideTable", new BsonDocument()
				.append("domain", new BsonString("/entities/weird%7Ci.d./children"))
				.append("valuesById", new BsonDocument())
			)
			.append("phantoms", new BsonDocument()
				.append("id", new BsonString(WEIRD_ID + "_phantoms"))
			)
			.append("optionals", new BsonDocument()
				.append("id", new BsonString(WEIRD_ID + "_optionals"))
			)
			.append("implicitRefs", new BsonDocument()
				.append("id", new BsonString(WEIRD_ID + "_implicitRefs"))
			)
			.append("variant", new BsonDocument()
				.append("variant1", new BsonDocument("stringField", new BsonString("variantCase1String")))
			)
			;

		ArrayList<String> dottedName = BsonFormatter.dottedFieldNameSegments(weirdRef, weirdRef.path().length(), bosk.rootReference());
		BsonDocument expected = new BsonDocument()
			.append(dottedName.get(dottedName.size()-1), weirdDoc);
		assertEquals(expected, actual);
	}
}
