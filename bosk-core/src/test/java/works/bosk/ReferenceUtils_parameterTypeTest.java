package works.bosk;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import works.bosk.libtesting.AbstractBoskTest;
import works.bosk.util.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.ReferenceUtils.parameterType;
import static works.bosk.ReferenceUtils.rawClass;

class ReferenceUtils_parameterTypeTest extends AbstractBoskTest {

	@Test
	void testDirectInterfaceParameters() throws SecurityException {
		Type type = new BinaryInterface<String,Integer>() {}.getClass().getGenericInterfaces()[0];
		checkStringAndIntegerParameters(type, BinaryInterface.class);
	}

	@Test
	void testDirectClassParameters() throws SecurityException {
		@SuppressWarnings("unused")
		abstract class BinaryClass<T0,T1> {}
		Type type = new BinaryClass<String,Integer>() {}.getClass().getGenericSuperclass();
		checkStringAndIntegerParameters(type, BinaryClass.class);
	}

	@Test
	void testExtendedClassConcrete() throws SecurityException {
		@SuppressWarnings("unused")
		abstract class BinaryClass<T0,T1> {}

		// BinaryClass parameters are concrete types
		abstract class Subclass extends BinaryClass<String, Integer> {}

		Type type = new Subclass() {}.getClass().getGenericSuperclass();
		checkStringAndIntegerParameters(type, BinaryClass.class);
	}

	@Test
	void testImplementedInterfaceConcrete() throws SecurityException {
		// Binary parameters are concrete types
		abstract class Subclass implements BinaryInterface<String, Integer> {}

		Type type = new Subclass() {}.getClass().getGenericSuperclass();
		checkStringAndIntegerParameters(type, BinaryInterface.class);
	}

	@Test
	void testExtendedInterfaceConcrete() throws SecurityException {
		Type type = new ConcreteSub() {}.getClass().getGenericInterfaces()[0];
		checkStringAndIntegerParameters(type, BinaryInterface.class);
	}

	@Test
	void testExtendedClassVariable() throws SecurityException {
		@SuppressWarnings("unused")
		abstract class BinaryClass<T0,T1> {}

		// BinaryClass parameters are type variables
		abstract class Subclass<T0,T1> extends BinaryClass<T0, T1> {}

		Type type = new Subclass<String, Integer>() {}.getClass().getGenericSuperclass();
		checkStringAndIntegerParameters(type, BinaryClass.class);
	}

	@Test
	void testImplementedInterfaceVariable() throws SecurityException {
		// Binary parameters are type variables
		abstract class Subclass<T0,T1> implements BinaryInterface<T0, T1> {}

		Type type = new Subclass<String, Integer>() {}.getClass().getGenericSuperclass();
		checkStringAndIntegerParameters(type, BinaryInterface.class);
	}

	@Test
	void testExtendedInterfaceVariable() throws SecurityException {
		Type type = new VariableSub<String, Integer>() {}.getClass().getGenericInterfaces()[0];
		checkStringAndIntegerParameters(type, BinaryInterface.class);
	}

	@Test
	void testExtendedClassSwitcheroo() throws SecurityException {
		@SuppressWarnings("unused")
		abstract class BinaryClass<T0,T1> {}

		// Type parameters are reversed
		abstract class Subclass<T0,T1> extends BinaryClass<T1, T0> {}

		Type type = new Subclass<Integer, String>() {}.getClass().getGenericSuperclass();
		checkStringAndIntegerParameters(type, BinaryClass.class);
	}

	@Test
	void testParameterWithParameters() throws NoSuchFieldException {
		abstract class TestClass {
			@SuppressWarnings("unused")
			public SideTableReference<Entity, String> sideTable;
		}
		Type type = TestClass.class.getDeclaredField("sideTable").getGenericType();
		Type targetType = ReferenceUtils.parameterType(type, Reference.class, 0);
		Type keyType = ReferenceUtils.parameterType(targetType, SideTable.class, 0);
		Type valueType = ReferenceUtils.parameterType(targetType, SideTable.class, 1);
		assertEquals(Entity.class, keyType);
		assertEquals(String.class, valueType);
	}

	@Test
	void directCases() {
		assertParameter0IsString(Types.parameterizedType(Class1.class, String.class));
		assertParameter0IsString(Types.parameterizedType(Interface1.class, String.class));
		assertParameter0IsString(Types.parameterizedType(GenericExtendingClass1.class, String.class));
		assertParameter0IsString(Types.parameterizedType(GenericImplementingInterface1.class, String.class));
		assertParameter0IsString(Types.parameterizedType(GenericExtendingInterface1.class, String.class));

		assertDirectParameterEquals(Integer.class, Types.parameterizedType(GenericExtendingClass2OfString.class, Integer.class), 0);
		assertDirectParameterEquals(Integer.class, Types.parameterizedType(GenericImplementingInterface2OfString.class, Integer.class), 0);
		assertDirectParameterEquals(Integer.class, Types.parameterizedType(GenericExtendingInterface2OfString.class, Integer.class), 0);

		assertDirectParameterEquals(Class1.class, Types.parameterizedType(TwoParameters.class, Class1.class, Class2.class), 0);
		assertDirectParameterEquals(Class2.class, Types.parameterizedType(TwoParameters.class, Class1.class, Class2.class), 1);
	}

	@Test
	void inheritedParameters() {
		assertParameterTypeIsString(ClassExtendingClass1.class, Class1.class, 0);
		assertParameterTypeIsString(ClassImplementingInterface1.class, Interface1.class, 0);
		assertParameterTypeIsString(InterfaceExtendingInterface1.class, Interface1.class, 0);

	}

	@Test
	void passthruParameters() {
		assertParameterTypeIsString(Types.parameterizedType(GenericExtendingClass1.class, String.class), Class1.class, 0);
		assertParameterTypeIsString(Types.parameterizedType(GenericImplementingInterface1.class, String.class), Interface1.class, 0);
		assertParameterTypeIsString(Types.parameterizedType(GenericExtendingInterface1.class, String.class), Interface1.class, 0);
	}

	@Test
	void classIneritingLots() {
		assertParameterTypeEquals(Integer.class, ClassInheritingLots.class, Class1.class, 0);
		assertParameterTypeEquals(Long.class, ClassInheritingLots.class, GenericExtendingInterface1.class, 0);
		assertParameterTypeEquals(Long.class, ClassInheritingLots.class, Interface1.class, 0);
		assertParameterTypeEquals(Float.class, ClassInheritingLots.class, GenericExtendingInterface2OfString.class, 0);
		assertParameterTypeEquals(String.class, ClassInheritingLots.class, Interface2.class, 0);
	}

	@Test
	void switcheroo() {
		assertParameterTypeEquals(Class2.class, Types.parameterizedType(Switcheroo.class, Class1.class, Class2.class), TwoParameters.class, 0);
		assertParameterTypeEquals(Class1.class, Types.parameterizedType(Switcheroo.class, Class1.class, Class2.class), TwoParameters.class, 1);
	}

	@Test
	void errorCases() {
		assertThrows(AssertionError.class, () -> parameterType(ClassInheritingLots.class, ArrayList.class, 0));
		assertThrows(AssertionError.class, () -> parameterType(ClassInheritingLots.class, List.class, 0));
	}

	void assertParameter0IsString(Type type) {
		assertDirectParameterEquals(String.class, type, 0);

	}
	void assertDirectParameterEquals(Type expected, Type type, int index) {
		assertParameterTypeEquals(expected, type, rawClass(type), index);
	}

	private void checkStringAndIntegerParameters(Type type, Class<?> expectedClass) {
		Assertions.assertEquals(String.class,  ReferenceUtils.parameterType(type, expectedClass, 0));
		Assertions.assertEquals(Integer.class, ReferenceUtils.parameterType(type, expectedClass, 1));
		assertThrows(IndexOutOfBoundsException.class, ()-> ReferenceUtils.parameterType(type, expectedClass, -1));
		assertThrows(IndexOutOfBoundsException.class, ()-> ReferenceUtils.parameterType(type, expectedClass, 2));
	}

	private void assertParameterTypeIsString(Type type, Class<?> genericClass, int index) {
		assertEquals(String.class, parameterType(type, genericClass, index));
	}

	private void assertParameterTypeEquals(Type expected, Type type, Class<?> genericClass, int index) {
		assertEquals(expected, parameterType(type, genericClass, index));
	}

	// Can't declare method-local interfaces

	@SuppressWarnings("unused") // Type parameters T0 and T1 aren't used, but that's ok
	interface BinaryInterface<T0,T1> {}

	interface ConcreteSub extends BinaryInterface<String, Integer> {}

	interface VariableSub<T0,T1> extends BinaryInterface<T0, T1> {}

	private static class Class1<T> {}
	private static class Class2<T> {}
	private interface Interface1<T> {}
	private interface Interface2<T> {}

	private static class ClassExtendingClass1 extends Class1<String> {}
	private static class ClassImplementingInterface1 implements Interface1<String> {}
	private interface InterfaceExtendingInterface1 extends Interface1<String> {}

	private static class GenericExtendingClass1<T> extends Class1<T> {}
	private static class GenericImplementingInterface1<T> implements Interface1<T> {}
	private interface GenericExtendingInterface1<T> extends Interface1<T> {}
	private static class GenericExtendingClass2OfString<T> extends Class2<String> {}
	private static class GenericImplementingInterface2OfString<T> implements Interface2<String> {}
	private interface GenericExtendingInterface2OfString<T> extends Interface2<String> {}

	private static class ClassInheritingLots extends Class1<Integer> implements GenericExtendingInterface1<Long>, GenericExtendingInterface2OfString<Float> {}

	private interface TwoParameters<A,B> {}
	private interface Switcheroo<A,B> extends TwoParameters<B,A> {}

}
