package works.bosk.junit;

import java.lang.reflect.Parameter;
import java.util.List;
import org.junit.jupiter.api.Disabled;

// TODO: automate this test
@Disabled("Used for manual testing of correlated parameter injection")
@InjectFrom({
	CorrelatedParameterTest.Injector1.class,
	CorrelatedParameterTest.Injector2.class,
	CorrelatedParameterTest.Injector3.class,
	CorrelatedParameterTest.Injector4.class
})
public class CorrelatedParameterTest {

	@InjectedTest
	void test1(long from1, String from4) {
		System.out.println("test1(" + from1 + "," + from4 + ")");
	}

	@InjectedTest
	void test2(long from1) {
		System.out.println("test2(" + from1 + ")");
	}

	record Injector1() implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(long.class);
		}

		@Override
		public List<Long> values() {
			return List.of(1001L, 1002L);
		}
	}

	record Injector2(long from1) implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(String.class);
		}

		@Override
		public List<String> values() {
			return List.of("2(" + from1 + ")");
		}
	}

	record Injector3(long from1, String from2) implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(String.class);
		}

		@Override
		public List<String> values() {
			return List.of("3a(" + from1 + "," + from2 + ")",
				"3b(" + from1 + "," + from2 + ")");
		}
	}

	record Injector4(long from1, String from3) implements ParameterInjector {
		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType().equals(String.class);
		}

		@Override
		public List<String> values() {
			return List.of("4(" + from1 + "," + from3 + ")");
		}
	}

}
