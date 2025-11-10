package works.bosk.boson.codec;

import java.lang.reflect.Parameter;
import java.util.List;
import works.bosk.boson.types.PrimitiveType;
import works.bosk.junit.ParameterInjector;

import static works.bosk.boson.types.PrimitiveType.BOOLEAN;
import static works.bosk.boson.types.PrimitiveType.BYTE;
import static works.bosk.boson.types.PrimitiveType.CHAR;
import static works.bosk.boson.types.PrimitiveType.DOUBLE;
import static works.bosk.boson.types.PrimitiveType.FLOAT;
import static works.bosk.boson.types.PrimitiveType.INT;
import static works.bosk.boson.types.PrimitiveType.LONG;
import static works.bosk.boson.types.PrimitiveType.SHORT;

public class PrimitiveTypeInjector implements ParameterInjector {
	@Override
	public boolean supportsParameter(Parameter parameter) {
		return parameter.getType() == PrimitiveType.class;
	}

	@Override
	public List<Object> values() {
		return List.of(
			BOOLEAN,
			BYTE,
			SHORT,
			INT,
			LONG,
			FLOAT,
			DOUBLE,
			CHAR
		);
	}
}
