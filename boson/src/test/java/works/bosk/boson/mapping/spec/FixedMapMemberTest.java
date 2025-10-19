package works.bosk.boson.mapping.spec;

import java.lang.invoke.MethodHandles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.boson.types.DataType.INT;

class FixedMapMemberTest {

	@Test
	void forComponent() throws Throwable {
		record TestRecord(int i) {}
		var fmm = FixedMapMember.forComponent(TestRecord.class.getRecordComponents()[0], MethodHandles.lookup());
		assertEquals(INT, fmm.dataType());
		assertEquals(new TypeRefNode(INT), fmm.valueSpec());
		assertEquals(123, (int)fmm.accessor().handle().invokeExact(new TestRecord(123)));
	}

}
