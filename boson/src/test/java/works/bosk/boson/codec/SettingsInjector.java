package works.bosk.boson.codec;

import java.lang.reflect.Parameter;
import java.util.List;
import java.util.stream.Stream;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.junit.ParameterInjector;

record SettingsInjector() implements ParameterInjector {
	@Override
	public boolean supportsParameter(Parameter parameter) {
		return parameter.getType().equals(TypeMap.Settings.class);
	}

	@Override
	public List values() {
		return Stream.of(
			new TypeMap.Settings(false, false, false, false, false),
			new TypeMap.Settings(true, false, false, false, false),
			new TypeMap.Settings(true, false, false, true, false),
			new TypeMap.Settings(true, false, true, false, false),
			new TypeMap.Settings(true, true, true, true, false)
		).toList();
	}
}
