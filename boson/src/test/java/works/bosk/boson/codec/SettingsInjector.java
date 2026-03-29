package works.bosk.boson.codec;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.junit.Injector;

record SettingsInjector() implements Injector {
	@Override
	public boolean supports(AnnotatedElement element, Class<?> elementType) {
		return elementType.equals(TypeMap.Settings.class);
	}

	@Override
	public List<TypeMap.Settings> values() {
		return List.of(
			new TypeMap.Settings(false, false, false, false, false),
			new TypeMap.Settings(true, false, false, false, false),
			new TypeMap.Settings(true, false, false, true, false),
			new TypeMap.Settings(true, false, true, false, false),
			new TypeMap.Settings(true, true, true, true, false)
		);
	}
}
