package works.bosk;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import works.bosk.exceptions.DeserializationException;
import works.bosk.exceptions.InvalidTypeException;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateTreeSerializerTest {

	@Test
	void nullValue_isDistinguishedFromMissingField() throws InvalidTypeException {
		// An explicit null value for a present field must not be conflated with an
		// absent field: parameterValueList must reject it rather than report "Missing field".
		StateTreeSerializer serializer = new StateTreeSerializer() {};
		Bosk<TestRoot> bosk = new Bosk<>("test", TestRoot.class, this::initialState, BoskConfig.<TestRoot>builder().build());

		LinkedHashMap<String, RecordComponent> componentsByName = new LinkedHashMap<>();
		for (RecordComponent component: HasRequiredField.class.getRecordComponents()) {
			componentsByName.put(component.getName(), component);
		}
		Map<String, Object> parameterValuesByName = new LinkedHashMap<>();
		parameterValuesByName.put("requiredField", null);

		DeserializationException e = assertThrows(DeserializationException.class, () ->
			serializer.parameterValueList(HasRequiredField.class, parameterValuesByName, componentsByName, bosk));
		assertThat(e.getMessage(), containsString("must not be null"));
	}

	public record TestRoot(Catalog<Item> items) implements StateTreeNode { }
	public record Item(Identifier id) implements Entity { }

	public record HasRequiredField(String requiredField) implements StateTreeNode { }

	private TestRoot initialState(Bosk<TestRoot> bosk) {
		return new TestRoot(Catalog.empty());
	}

}
