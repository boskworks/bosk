package works.bosk.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.ListingReference;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.SideTableReference;
import works.bosk.StateTreeNode;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;
import works.bosk.annotations.ReferencePath;
import works.bosk.annotations.VariantCaseMap;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.jackson.JsonNodeSurgeon.NodeInfo;
import works.bosk.jackson.JsonNodeSurgeon.NodeLocation.ArrayElement;
import works.bosk.jackson.JsonNodeSurgeon.NodeLocation.NonexistentParent;
import works.bosk.jackson.JsonNodeSurgeon.NodeLocation.ObjectMember;
import works.bosk.jackson.JsonNodeSurgeon.NodeLocation.Root;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.BoskTestUtils.boskName;
import static works.bosk.ListingEntry.LISTING_ENTRY;

// TODO: Test mutation methods too
public class JsonNodeSurgeonTest {
	Bosk<JsonRoot> bosk;
	Refs refs;
	JacksonSerializer jacksonSerializer;
	ObjectMapper mapper;
	JsonNodeSurgeon surgeon;

	@BeforeEach
	void setUp() throws InvalidTypeException {
		bosk = new Bosk<JsonRoot>(
			boskName(),
			JsonRoot.class,
			b->JsonRoot.empty(b.buildReferences(Refs.class)),
			Bosk.simpleDriver());
		refs = bosk.buildReferences(Refs.class);
		jacksonSerializer = new JacksonSerializer();
		mapper = new ObjectMapper();
		mapper.registerModule(jacksonSerializer.moduleFor(bosk));
		surgeon = new JsonNodeSurgeon();
	}

	public record JsonRoot(
		Identifier id,
		String string,
		Catalog<JsonEntity> catalog,
		Listing<JsonEntity> listing,
		SideTable<JsonEntity, JsonEntity> sideTable,
		TaggedUnion<JsonVariant> variant
	) implements StateTreeNode {
		public static final JsonRoot empty(Refs refs) {
			return new JsonRoot(
				Identifier.from("testID"),
				"testString",
				Catalog.empty(),
				Listing.empty(refs.catalog()),
				SideTable.empty(refs.catalog()),
				TaggedUnion.of(new JsonVariant.JsonVariant1()));
		}
	}

	public record JsonEntity(
		Identifier id
	) implements Entity {}

	public sealed interface JsonVariant extends VariantCase {
		@VariantCaseMap
		static final MapValue<Class<?>> variants = MapValue.copyOf(Map.of(
			"variant1", JsonVariant1.class,
			"variant2", JsonVariant2.class
		));
		public record JsonVariant1() implements JsonVariant {
			@Override public String tag() { return "variant1"; }
		}
		public record JsonVariant2() implements JsonVariant {
			@Override public String tag() { return "variant2"; }
		}
	}

	public interface Refs {
		@ReferencePath("/id") Reference<Identifier> id();
		@ReferencePath("/string") Reference<String> string();
		@ReferencePath("/catalog") CatalogReference<JsonEntity> catalog();
		@ReferencePath("/catalog/-jsonEntity-") Reference<JsonEntity> catalogEntry(Identifier jsonEntity);
		@ReferencePath("/catalog/-jsonEntity-/id") Reference<Identifier> catalogEntryID(Identifier jsonEntity);
		@ReferencePath("/listing") ListingReference<JsonEntity> listing();
		@ReferencePath("/listing/-jsonEntity-") Reference<ListingEntry> listingEntry(Identifier jsonEntity);
		@ReferencePath("/sideTable") SideTableReference<JsonEntity, JsonEntity> sideTable();
		@ReferencePath("/sideTable/-jsonEntity-") Reference<JsonEntity> sideTableEntry(Identifier jsonEntity);
		@ReferencePath("/sideTable/-jsonEntity-/id") Reference<Identifier> sideTableEntryID(Identifier jsonEntity);
		@ReferencePath("/variant") Reference<TaggedUnion<JsonVariant>> variant();
		@ReferencePath("/variant/variant1") Reference<JsonVariant.JsonVariant1> variant1();
		@ReferencePath("/variant/variant2") Reference<JsonVariant.JsonVariant2> variant2();
	}

	@Test
	void root() throws IOException, InterruptedException {
		JsonNode doc = boskContents();
		NodeInfo expected = NodeInfo.plain(new Root());
		NodeInfo actual = surgeon.nodeInfo(doc, bosk.rootReference());
		assertEquals(expected, actual);
		assertEquals(doc, surgeon.valueNode(doc, bosk.rootReference()));
	}

	@Test
	void id() throws IOException, InterruptedException {
		JsonNode doc = boskContents();
		NodeInfo expected = NodeInfo.plain(new ObjectMember((ObjectNode) doc, "id"));
		NodeInfo actual = surgeon.nodeInfo(doc, refs.id());
		assertEquals(expected, actual);
	}

	@Test
	void string() throws IOException, InterruptedException {
		JsonNode doc = boskContents();
		NodeInfo expected = NodeInfo.plain(new ObjectMember((ObjectNode) doc, "string"));
		NodeInfo actual = surgeon.nodeInfo(doc, refs.string());
		assertEquals(expected, actual);
	}

	@Test
	void catalog() throws IOException, InterruptedException {
		JsonNode doc = boskContents();
		NodeInfo expected = NodeInfo.plain(new ObjectMember((ObjectNode) doc, "catalog"));
		NodeInfo actual = surgeon.nodeInfo(doc, refs.catalog());
		assertEquals(expected, actual);
	}

	@Test
	void catalogEntry() throws IOException, InterruptedException {
		Identifier id = Identifier.from("testEntry");
		bosk.driver().submitReplacement(refs.catalogEntry(id), new JsonEntity(id));
		JsonNode doc = boskContents();
		JsonNode catalogArray = doc.get("catalog");
		JsonNode catalogEntry = catalogArray.get(0);
		{
			NodeInfo expected = NodeInfo.wrapped(
				new ObjectMember((ObjectNode) catalogEntry, "testEntry"),
				new ArrayElement((ArrayNode) catalogArray, 0));
			NodeInfo actual = surgeon.nodeInfo(doc, refs.catalogEntry(id));
			assertEquals(expected, actual);
		}
		{
			NodeInfo expected = NodeInfo.wrapped(
				new NonexistentParent(),
				new ArrayElement((ArrayNode) catalogArray, 1));
			NodeInfo actual = surgeon.nodeInfo(doc, refs.catalogEntry(Identifier.from("NONEXISTENT")));
			assertEquals(expected, actual);
		}
		{
			NodeInfo expected = NodeInfo.plain(new NonexistentParent());
			NodeInfo actual = surgeon.nodeInfo(doc, refs.catalogEntryID(Identifier.from("NONEXISTENT")));
			assertEquals(expected, actual);
		}
	}

	@Test void listing() throws IOException, InterruptedException {
		JsonNode doc = boskContents();
		NodeInfo expected = NodeInfo.plain(new ObjectMember((ObjectNode) doc, "listing"));
		NodeInfo actual = surgeon.nodeInfo(doc, refs.listing());
		assertEquals(expected, actual);
	}

	@Test
	void listingEntry() throws IOException, InterruptedException {
		Identifier id = Identifier.from("testEntry");
		bosk.driver().submitReplacement(refs.listingEntry(id), LISTING_ENTRY);
		JsonNode doc = boskContents();
		JsonNode idsArray = doc.get("listing").get("ids");
		{
			NodeInfo expected = NodeInfo.idOnly(new ArrayElement((ArrayNode) idsArray, 0));
			NodeInfo actual = surgeon.nodeInfo(doc, refs.listingEntry(id));
			assertEquals(expected, actual);
		}
		{
			NodeInfo expected = NodeInfo.idOnly(new ArrayElement((ArrayNode) idsArray, 1));
			NodeInfo actual = surgeon.nodeInfo(doc, refs.listingEntry(Identifier.from("NONEXISTENT")));
			assertEquals(expected, actual);
		}
	}

	@Test void sideTable() throws IOException, InterruptedException {
		JsonNode doc = boskContents();
		NodeInfo expected = NodeInfo.plain(new ObjectMember((ObjectNode) doc, "sideTable"));
		NodeInfo actual = surgeon.nodeInfo(doc, refs.sideTable());
		assertEquals(expected, actual);
	}

	@Test
	void sideTableEntry() throws IOException, InterruptedException {
		Identifier id = Identifier.from("testKey");
		bosk.driver().submitReplacement(refs.sideTableEntry(id), new JsonEntity(Identifier.from("testValue")));
		JsonNode doc = boskContents();
		JsonNode valuesById = doc.get("sideTable").get("valuesById");
		JsonNode entry = valuesById.get(0);
		{
			NodeInfo expected = NodeInfo.wrapped(
				new ObjectMember((ObjectNode) entry, "testKey"),
				new ArrayElement((ArrayNode) valuesById, 0));
			NodeInfo actual = surgeon.nodeInfo(doc, refs.sideTableEntry(id));
			assertEquals(expected, actual);
		}
		{
			NodeInfo expected = NodeInfo.wrapped(
				new NonexistentParent(),
				new ArrayElement((ArrayNode) valuesById, 1));
			NodeInfo actual = surgeon.nodeInfo(doc, refs.sideTableEntry(Identifier.from("NONEXISTENT")));
			assertEquals(expected, actual);
		}
		{
			NodeInfo expected = NodeInfo.plain(new NonexistentParent());
			NodeInfo actual = surgeon.nodeInfo(doc, refs.sideTableEntryID(Identifier.from("NONEXISTENT")));
			assertEquals(expected, actual);
		}
	}

	@Test
	void variant() throws IOException, InterruptedException {
		bosk.driver().submitReplacement(refs.variant(), TaggedUnion.of(new JsonVariant.JsonVariant1()));
		JsonNode doc = boskContents();
		{
			NodeInfo expected = NodeInfo.plain(new ObjectMember((ObjectNode) doc, "variant"));
			NodeInfo actual = surgeon.nodeInfo(doc, refs.variant());
			assertEquals(expected, actual);
		}
		{
			NodeInfo expected = NodeInfo.plain(new ObjectMember((ObjectNode) doc.get("variant"), "variant1"));
			NodeInfo actual = surgeon.nodeInfo(doc, refs.variant1());
			assertEquals(expected, actual);
		}
		{
			NodeInfo expected = NodeInfo.plain(new ObjectMember((ObjectNode) doc.get("variant"), "variant2"));
			NodeInfo actual = surgeon.nodeInfo(doc, refs.variant2());
			assertEquals(expected, actual);
		}
	}

	JsonNode boskContents() throws IOException, InterruptedException {
		bosk.driver().flush();
		try (var __ = bosk.readContext()) {
			return mapper.convertValue(bosk.rootReference().value(), JsonNode.class);
		}
	}
}
