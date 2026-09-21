package works.bosk.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoskMaintenanceRequestMatcherTest {
	@Test
	void matchesBasePath() {
		assertTrue(matches("/bosk", "/bosk"));
	}

	@Test
	void matchesBasePathWithTrailingSlash() {
		assertTrue(matches("/bosk", "/bosk/"));
	}

	@Test
	void matchesSubPath() {
		assertTrue(matches("/bosk", "/bosk/targets/plain"));
	}

	@Test
	void doesNotMatchOtherPath() {
		assertFalse(matches("/bosk", "/other"));
	}

	@Test
	void doesNotMatchPathWithSamePrefix() {
		assertFalse(matches("/bosk", "/bosky"));
	}

	@Test
	void missingLeadingSlashIsNormalized() {
		assertTrue(matches("bosk", "/bosk/targets"));
	}

	@Test
	void trailingSlashIsNormalized() {
		assertTrue(matches("/bosk/", "/bosk/targets"));
	}

	@Test
	void rootPathMatchesEverything() {
		assertTrue(matches("/", "/anything"));
	}

	private static boolean matches(String path, String requestUri) {
		return new BoskMaintenanceRequestMatcher(path)
			.matches(new MockHttpServletRequest("GET", requestUri));
	}
}
