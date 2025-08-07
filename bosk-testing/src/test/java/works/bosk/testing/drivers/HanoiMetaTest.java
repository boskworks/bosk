package works.bosk.testing.drivers;

import works.bosk.Bosk;

public class HanoiMetaTest extends HanoiTest {
	public HanoiMetaTest() {
		driverFactory = Bosk.simpleDriver();
	}
}
