package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.TypeReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.boson.types.DataType.STRING;

@TestMethodOrder(OrderAnnotation.class)
class TypedHandlesTest {

	@Test
	@Order(1) // We use constants in other tests
	void constant() {
		TypedHandle th = TypedHandles.constant(STRING, "Hello");
		assertEquals("Hello", th.invoke());
	}

	@Test
	void canonicalConstructor() {
		record TestRecord(int a, String b){}
		var lookup = MethodHandles.lookup();
		TypedHandle th = TypedHandles.canonicalConstructor(TestRecord.class, lookup);
		var actual = th.invoke(123, "Hello");
		var expected = new TestRecord(123, "Hello");
		assertEquals(expected, actual);
	}

	@Test
	void componentAccessor() {
		record TestRecord(int a, String b){}
		var lookup = MethodHandles.lookup();
		var rcA = TestRecord.class.getRecordComponents()[0];
		var rcB = TestRecord.class.getRecordComponents()[1];
		TypedHandle thA = TypedHandles.componentAccessor(rcA, lookup);
		TypedHandle thB = TypedHandles.componentAccessor(rcB, lookup);
		var record = new TestRecord(456, "World");
		var actualA = thA.invoke(record);
		var actualB = thB.invoke(record);
		assertEquals(456, actualA);
		assertEquals("World", actualB);
	}

	@Test
	void notEquals() {
		TypedHandle notEquals = TypedHandles.notEquals();
		assertTrue((boolean) notEquals.invoke("not", "equals"));
		assertFalse((boolean) notEquals.invoke("equals", "equals"));

		TypedHandle notEqualsHello = TypedHandles.notEquals(TypedHandles.constant(STRING, "Hello"));
		assertFalse((boolean) notEqualsHello.invoke("Hello"));
		assertTrue((boolean) notEqualsHello.invoke("World"));
	}

	@Test
	void supplier() {
		TypedHandle th = TypedHandles.supplier(STRING, () -> "SuppliedValue");
		assertEquals("SuppliedValue", th.invoke());
	}

	@Test
	void consumer() {
		AtomicReference<String> ref = new AtomicReference<>("initial");
		TypedHandle th = TypedHandles.consumer(STRING, ref::set);
		th.invoke("NewValue");
		assertEquals("NewValue", ref.get());
	}

	@Test
	void biConsumer() {
		AtomicReference<String> ref1 = new AtomicReference<>("initial_ref1");
		AtomicReference<String> ref2 = new AtomicReference<>("initial_ref2");
		var refType = DataType.known(new TypeReference<AtomicReference<String>>(){});
		TypedHandle th = TypedHandles.<AtomicReference<String>, String>biConsumer(refType, STRING,
			AtomicReference::set);

		assertEquals("initial_ref1", ref1.get());
		assertEquals("initial_ref2", ref2.get());

		th.invoke(ref1, "Value1");

		assertEquals("Value1", ref1.get());
		assertEquals("initial_ref2", ref2.get());

		th.invoke(ref2, "Value2");

		assertEquals("Value1", ref1.get());
		assertEquals("Value2", ref2.get());
	}

	@Test
	void callable() {
		AtomicInteger ai = new AtomicInteger(0);
		TypedHandle th = TypedHandles.callable(DataType.INT,
			ai::get);
		assertEquals(0, th.invoke());
		ai.set(123);
		assertEquals(123, th.invoke());
	}

	@Test
	void function() {
		AtomicInteger ai1 = new AtomicInteger(101);
		AtomicInteger ai2 = new AtomicInteger(102);
		TypedHandle th = TypedHandles.function(DataType.known(AtomicInteger.class), DataType.INT,
			AtomicInteger::get);

		assertEquals(101, th.invoke(ai1));
		assertEquals(102, th.invoke(ai2));

		ai1.set(201);

		assertEquals(201, th.invoke(ai1));
		assertEquals(102, th.invoke(ai2));

		ai2.set(202);

		assertEquals(201, th.invoke(ai1));
		assertEquals(202, th.invoke(ai2));
	}

	@Test
	void biFunction() {
		AtomicInteger ai = new AtomicInteger(0);
		TypedHandle th = TypedHandles.biFunction(DataType.known(AtomicInteger.class), DataType.INT, DataType.INT,
			AtomicInteger::getAndSet);

		assertEquals(0, th.invoke(ai, 10));
		assertEquals(10, ai.get());
	}

	@Test
	void predicate() {
		AtomicBoolean ab = new AtomicBoolean(false);
		TypedHandle th = TypedHandles.predicate(DataType.known(AtomicBoolean.class),
			AtomicBoolean::get);

		assertFalse((boolean) th.invoke(ab));
		ab.set(true);
		assertTrue((boolean) th.invoke(ab));
	}
}
