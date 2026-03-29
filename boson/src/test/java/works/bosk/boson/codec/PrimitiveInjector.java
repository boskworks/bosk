package works.bosk.boson.codec;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import works.bosk.junit.Injector;

public record PrimitiveInjector() implements Injector {
	@Override
	public boolean supports(AnnotatedElement element, Class<?> elementType) {
		return elementType.equals(PrimitiveNumber.class);
	}

	@Override
	public List<PrimitiveNumber> values() {
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
