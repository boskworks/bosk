package works.bosk.jackson;

import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;
import works.bosk.BoskDriver;
import works.bosk.testing.drivers.DriverConformanceTest;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonNodeDriverConformanceTest extends DriverConformanceTest {
	private JsonNodeDriver jsonNodeDriver;

	@BeforeEach
	void setUp() {
		driverFactory = (b,d) -> {
			BoskDriver result = JsonNodeDriver.<TestEntity>factory(new JacksonSerializer()).build(b, d);
			jsonNodeDriver = (JsonNodeDriver) result;
			return result;
		};
	}

	@Override
	protected void assertCorrectBoskContents() {
		super.assertCorrectBoskContents();
		JsonNode expected, actual;
		try (var _ = bosk.readSession()) {
			TestEntity boskRoot = bosk.rootReference().value();
			expected = jsonNodeDriver.mapper.convertValue(boskRoot, JsonNode.class);
			actual = jsonNodeDriver.contents;
		}
		assertEquals(expected, actual);
	}
}
