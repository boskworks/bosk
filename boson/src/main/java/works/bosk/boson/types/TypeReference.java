package works.bosk.boson.types;

@SuppressWarnings("unused") // The type parameter is used only via reflection
public abstract class TypeReference<T> {
	java.lang.reflect.Type reflectionType() {
		return ((java.lang.reflect.ParameterizedType) getClass()
			.getGenericSuperclass()).getActualTypeArguments()[0];
	}
}
