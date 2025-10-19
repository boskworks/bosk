package works.bosk.boson.codec.compiler;

import java.util.BitSet;
import org.junit.jupiter.api.Test;
import works.bosk.boson.codec.compiler.LocalVariableAllocator.LocalVariable;

import static java.lang.classfile.TypeKind.DOUBLE;
import static java.lang.classfile.TypeKind.INT;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalVariableAllocatorTest {

	@Test
	void basicFunctionality() {
		var allocator = new LocalVariableAllocator(2);
		assertEquals(slots(0,1), allocator.busy);
		try (var locals = allocator.newScope()) {
			assertEquals(slots(0,1), allocator.busy);
			assertEquals(slots(), locals.owned);

			assertEquals(new LocalVariable(INT, 2), locals.allocate(INT));
			assertEquals(slots(0,1,2), allocator.busy);
			assertEquals(slots(2), locals.owned);

			assertEquals(new LocalVariable(DOUBLE, 3), locals.allocate(DOUBLE));
			assertEquals(slots(0,1,2,3,4), allocator.busy);
			assertEquals(slots(2,3,4), locals.owned);
		}
		assertEquals(slots(0,1), allocator.busy);
	}

	@Test
	void nestedScopes() {
		var allocator = new LocalVariableAllocator(1);
		assertEquals(slots(0), allocator.busy);
		try (var outer = allocator.newScope()) {
			assertEquals(new LocalVariable(INT, 1), outer.allocate(INT));
			assertEquals(slots(0,1), allocator.busy);

			try (var inner = allocator.newScope()) {
				// Interleave inner and outer allocations to cause fragmentation
				assertEquals(new LocalVariable(INT, 2),
					inner.allocate(INT));
				assertEquals(slots(0,1,2), allocator.busy);

				assertEquals(new LocalVariable(INT, 3),
					outer.allocate(INT));
				assertEquals(slots(0,1,2,3), allocator.busy);

				assertEquals(new LocalVariable(DOUBLE, 4),
					inner.allocate(DOUBLE));
				assertEquals(slots(0,1,2,3,4,5), allocator.busy);

				assertEquals(new LocalVariable(INT, 6),
					outer.allocate(INT));
				assertEquals(slots(0,1,2,3,4,5,6), allocator.busy);
			}

			assertEquals(slots(0,1,3,6), allocator.busy, "Inner locals freed");

			assertEquals(new LocalVariable(DOUBLE, 4), outer.allocate(DOUBLE));
			assertEquals(slots(0,1,3,4,5,6), allocator.busy, "double gets two free slots");

			assertEquals(new LocalVariable(INT, 2), outer.allocate(INT));
			assertEquals(slots(0,1,2,3,4,5,6), allocator.busy, "int fills in hole");
		}
		assertEquals(slots(0), allocator.busy);
	}

	private BitSet slots(int... bits) {
		BitSet result = new BitSet();
		for (var bit: bits) {
			result.set(bit);
		}
		return result;
	}
}
