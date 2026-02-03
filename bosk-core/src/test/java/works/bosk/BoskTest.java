package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static works.bosk.testing.BoskTestUtils.boskName;

class BoskTest extends AbstractBoskTest {

	@Test
	void basicProperties() {
		String name = boskName();
		Bosk<TestRoot> bosk = new Bosk<>(
			name,
			TestRoot.class,
			AbstractBoskTest::initialRoot,
			BoskConfig.simple()
		);

		assertEquals(name, bosk.name());
		assertNotNull(bosk.instanceID());
		assertEquals(TestRoot.class, bosk.rootReference().targetClass());
	}

}
