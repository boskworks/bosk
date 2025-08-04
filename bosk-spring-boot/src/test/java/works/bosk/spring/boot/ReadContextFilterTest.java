package works.bosk.spring.boot;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import works.bosk.Bosk;
import works.bosk.StateTreeNode;
import works.bosk.testing.drivers.ReportingDriver;
import works.bosk.exceptions.NoReadContextException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadContextFilterTest {
	Bosk<State> bosk;
	ReadContextFilter filter;
	List<String> events;
	MockHttpServletRequest req;
	MockHttpServletResponse res;

	public record State() implements StateTreeNode {}

	@BeforeEach
	void setup() {
		events = new ArrayList<>();
		bosk = new Bosk<>(
			ReadContextFilterTest.class.getSimpleName(),
			State.class,
			b->new State(),
			ReportingDriver.factory(op -> events.add(op.getClass().getSimpleName())),
			Bosk.simpleRegistrar()
		);
		filter = new ReadContextFilter(bosk);
		req = new MockHttpServletRequest();
		res = new MockHttpServletResponse();
	}

	@Test
	void noHeaders_noOperations() throws ServletException, IOException {
		filter.doFilter(req, res, new ReportingFilterChain());
		assertEquals(List.of("filter chain"), events);
	}

	@Test
	void noCache_flushesFirst() throws ServletException, IOException {
		req.addHeader("Cache-Control", "no-cache");
		filter.doFilter(req, res, new ReportingFilterChain());
		assertEquals(List.of("FlushOperation", "filter chain"), events);
	}

	@Test
	void noCacheWeirdCase_flushes() throws ServletException, IOException {
		req.addHeader("Cache-Control", "nO-CAchE");
		filter.doFilter(req, res, new ReportingFilterChain());
		assertEquals(List.of("FlushOperation", "filter chain"), events);
	}


	@Test
	void otherCacheControl_noOperations() throws ServletException, IOException {
		req.addHeader("Cache-Control", "no-cash");
		filter.doFilter(req, res, new ReportingFilterChain());
		assertEquals(List.of("filter chain"), events);
	}

	@ParameterizedTest
	@ValueSource(strings = { "GET", "HEAD", "OPTIONS" })
	void readOnlyMethods_createReadContext(String method) throws ServletException, IOException {
		req.setMethod(method);
		filter.doFilter(req, res, (_, _) -> {
			assertEquals(new State(), bosk.rootReference().value());
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "POST", "PUT", "PATCH", "DELETE", "TRACE" })
	void otherMethods_noReadContext(String method) throws ServletException, IOException {
		req.setMethod(method);
		filter.doFilter(req, res, (_, _) -> {
			assertThrows(NoReadContextException.class, bosk.rootReference()::value);
		});
	}

	private class ReportingFilterChain extends MockFilterChain {
		@Override
		public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
			events.add("filter chain");
		}
	}

}
