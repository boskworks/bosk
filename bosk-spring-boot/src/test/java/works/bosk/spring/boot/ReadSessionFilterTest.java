package works.bosk.spring.boot;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.time.Duration;
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
import works.bosk.BoskConfig;
import works.bosk.StateTreeNode;
import works.bosk.drivers.ForwardingDriver;
import works.bosk.exceptions.FlushFailureException;
import works.bosk.exceptions.NoReadSessionException;
import works.bosk.exceptions.RetryableFlushException;
import works.bosk.exceptions.RetryableUpdateException;
import works.bosk.testing.drivers.ReportingDriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpHeaders.CACHE_CONTROL;
import static org.springframework.http.HttpHeaders.RETRY_AFTER;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

class ReadSessionFilterTest {
	// Arbitrary, and distinct from the 100 s default, so the test proves the
	// filter uses the exception's hint.
	private static final Duration RETRY_AFTER_HINT = Duration.ofSeconds(42);
	private static final Duration SUB_SECOND_HINT = Duration.ofMillis(200);
	private static final Duration FRACTIONAL_SECOND_HINT = Duration.ofMillis(1500);

	Bosk<State> bosk;
	ReadSessionFilter filter;
	List<String> events;
	MockHttpServletRequest req;
	MockHttpServletResponse res;

	public record State() implements StateTreeNode {}

	@BeforeEach
	void setup() {
		events = new ArrayList<>();
		bosk = new Bosk<>(
			ReadSessionFilterTest.class.getSimpleName(),
			State.class,
			_-> new State(),
			BoskConfig.<State>builder()
				.driverFactory(ReportingDriver.factory(op -> events.add(op.getClass().getSimpleName())))
				.build()
		);
		filter = new ReadSessionFilter(bosk);
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
		assertNull(res.getHeader(RETRY_AFTER), "A successful flush must not include Retry-After");
	}

	@Test
	void noCache_flushFailsWithRetryableFlushException_returns503() throws ServletException, IOException {
		useFailingFlush(new RetryableFlushException("Retry later", RETRY_AFTER_HINT));
		req.addHeader(CACHE_CONTROL, "no-cache");
		filter.doFilter(req, res, new ReportingFilterChain());
		assertEquals(503, res.getStatus());
		assertEquals(Long.toString(RETRY_AFTER_HINT.toSeconds()), res.getHeader(RETRY_AFTER));
		assertTrue(res.getContentType().startsWith(APPLICATION_JSON_VALUE),
			"The 503 response should have a JSON content type");
		assertEquals("{\"error\":\"bosk_flush_failed\","
			+ "\"message\":\"The server could not confirm that the state is up to date.\","
			+ "\"retryAfterSeconds\":" + RETRY_AFTER_HINT.toSeconds() + "}", res.getContentAsString());
		assertTrue(events.isEmpty(), "The filter chain must not be called after a flush failure");
	}

	@Test
	void retryableUpdate_returns503WithRetryAfter() throws IOException {
		filter.handleRetryableUpdate(new RetryableUpdateException("Retry later", RETRY_AFTER_HINT), res);
		assertEquals(503, res.getStatus());
		assertEquals(Long.toString(RETRY_AFTER_HINT.toSeconds()), res.getHeader(RETRY_AFTER));
		assertTrue(res.getContentType().startsWith(APPLICATION_JSON_VALUE),
			"The 503 response should have a JSON content type");
		assertEquals("{\"error\":\"bosk_update_failed\","
			+ "\"message\":\"The update could not be completed; retry later.\","
			+ "\"retryAfterSeconds\":" + RETRY_AFTER_HINT.toSeconds() + "}", res.getContentAsString());
	}

	@Test
	void noCache_flushFailsWithSubsecondHint_usesMinimumOfOneSecond() throws ServletException, IOException {
		useFailingFlush(new RetryableFlushException("Retry later", SUB_SECOND_HINT));
		req.addHeader(CACHE_CONTROL, "no-cache");
		filter.doFilter(req, res, new ReportingFilterChain());
		assertEquals("1", res.getHeader(RETRY_AFTER));
		assertTrue(res.getContentAsString().contains("\"retryAfterSeconds\":1"),
			"The body should use the one-second minimum");
	}

	@Test
	void noCache_flushFailsWithFractionalSecondHint_roundsUpToNextSecond() throws ServletException, IOException {
		useFailingFlush(new RetryableFlushException("Retry later", FRACTIONAL_SECOND_HINT));
		req.addHeader(CACHE_CONTROL, "no-cache");
		filter.doFilter(req, res, new ReportingFilterChain());
		assertEquals("2", res.getHeader(RETRY_AFTER));
		assertTrue(res.getContentAsString().contains("\"retryAfterSeconds\":2"),
			"The body should match the rounded-up Retry-After value");
	}

	@Test
	void noCache_flushFailsWithOtherFlushFailure_propagates() {
		useFailingFlush(new FlushFailureException("Some other flush failure"));
		req.addHeader(CACHE_CONTROL, "no-cache");
		assertThrows(FlushFailureException.class, () -> filter.doFilter(req, res, new ReportingFilterChain()));
		assertTrue(events.isEmpty(), "The filter chain must not be called after a flush failure");
	}

	@Test
	void noCache_flushFailsWithPlainIOException_propagates() {
		useFailingFlush(new IOException("Some other I/O failure"));
		req.addHeader(CACHE_CONTROL, "no-cache");
		assertThrows(IOException.class, () -> filter.doFilter(req, res, new ReportingFilterChain()));
		assertTrue(events.isEmpty(), "The filter chain must not be called after a flush failure");
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
	void readOnlyMethods_createReadSession(String method) throws ServletException, IOException {
		req.setMethod(method);
		filter.doFilter(req, res, (_, _) -> {
			assertEquals(new State(), bosk.rootReference().value());
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "POST", "PUT", "PATCH", "DELETE", "TRACE" })
	void otherMethods_noReadSession(String method) throws ServletException, IOException {
		req.setMethod(method);
		filter.doFilter(req, res, (_, _) -> {
			assertThrows(NoReadSessionException.class, bosk.rootReference()::value);
		});
	}

	private void useFailingFlush(IOException failure) {
		bosk = new Bosk<>(
			ReadSessionFilterTest.class.getSimpleName(),
			State.class,
			_ -> new State(),
			BoskConfig.<State>builder()
				.driverFactory((_, downstream) -> new ForwardingDriver(downstream) {
					@Override
					public void flush() throws IOException {
						throw failure;
					}
				})
				.build()
		);
		filter = new ReadSessionFilter(bosk);
	}

	private class ReportingFilterChain extends MockFilterChain {
		@Override
		public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
			events.add("filter chain");
		}
	}

}
