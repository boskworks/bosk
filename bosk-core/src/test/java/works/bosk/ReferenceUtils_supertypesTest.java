package works.bosk;

import java.io.Serializable;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.ReferenceUtils.supertypes;

class ReferenceUtils_supertypesTest {

	@Test
	void classesComeAfterTheTypesTheyExtend() {
		assertEquals(List.of(Object.class, Grandparent.class, Parent.class, Child.class),
			supertypes(Child.class).toList());
	}

	@Test
	void interfacesComeBeforeTheirImplementations() {
		assertEquals(List.of(Object.class, ParentInterface.class, ParentClass.class, ImplementingClass.class),
			supertypes(ImplementingClass.class).toList());
	}

	@Test
	void eachTypeAppearsOnceInADiamond() {
		// Left and Right both reach Shared, which must not appear twice.
		assertEquals(List.of(Object.class, Shared.class, Left.class, Right.class, Diamond.class),
			supertypes(Diamond.class).toList());
	}

	@Test
	void objectIsIncluded() {
		assertEquals(List.of(Object.class), supertypes(Object.class).toList());
	}

	@Test
	void primitiveType() {
		assertEquals(List.of(int.class), supertypes(int.class).toList());
	}

	@Test
	void arrayType() {
		assertEquals(List.of(Object.class, Cloneable.class, Serializable.class, String[].class),
			supertypes(String[].class).toList());
	}

	static class Grandparent {}

	static class Parent extends Grandparent {}

	static class Child extends Parent {}

	interface ParentInterface {}

	static class ParentClass implements ParentInterface {}

	static class ImplementingClass extends ParentClass {}

	interface Shared {}

	interface Left extends Shared {}

	interface Right extends Shared {}

	static class Diamond implements Left, Right {}
}
