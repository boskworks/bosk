package works.bosk.boson.codec;

import java.lang.reflect.Parameter;
import java.util.List;
import works.bosk.junit.ParameterInjector;

public record PrimitiveInjector() implements ParameterInjector {
	@Override
	public boolean supportsParameter(Parameter parameter) {
		return parameter.getType().equals(PrimitiveNumber.class);
	}

	@Override
	public List<Object> values() {
		return List.of(
			new PrimitiveNumber("int", int.class, Integer.class, 123, "123"),
			new PrimitiveNumber("long", long.class, Long.class, 123L, "123"),
			new PrimitiveNumber("float", float.class, Float.class, 123.0F, "123.0"),
			new PrimitiveNumber("double", double.class, Double.class, 123.0D, "123.0")
		);
	}

	public record PrimitiveNumber(
		String name,
		Class<?> type,
		Class<?> boxedType,
		Object value,
		String json
	) { }
}
