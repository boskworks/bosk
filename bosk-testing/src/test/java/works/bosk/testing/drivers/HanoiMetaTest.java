package works.bosk.testing.drivers;

import static works.bosk.BoskConfig.simpleDriver;

public class HanoiMetaTest extends HanoiTest {
	public HanoiMetaTest() {
		driverFactory = simpleDriver();
	}
}
