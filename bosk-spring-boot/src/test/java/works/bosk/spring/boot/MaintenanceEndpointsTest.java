package works.bosk.spring.boot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.StateTreeNode;
import works.bosk.jackson.JacksonSerializer;

import static org.springframework.http.HttpHeaders.CACHE_CONTROL;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.IF_MATCH;
import static org.springframework.http.HttpHeaders.IF_NONE_MATCH;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static works.bosk.testing.BoskTestUtils.boskName;

@SpringBootTest(
	classes = MaintenanceEndpointsTest.TestConfig.class,
	properties = {
		"bosk.web.maintenance.access=AUTHENTICATED",
		"bosk.web.maintenance.path=/bosk"
	})
@AutoConfigureMockMvc
class MaintenanceEndpointsTest {
	@Autowired
	MockMvc mockMvc;

	@Autowired
	Bosk<State> bosk;

	@Autowired
	ObjectMapper mapper;

	public record Target(Identifier id, String name) implements Entity {}

	public record Versioned(Identifier id, Identifier revision, String value) implements Entity {}

	public record State(Catalog<Target> targets, Catalog<Versioned> versioned) implements StateTreeNode {}

	static State initialState() {
		return new State(
			Catalog.of(
				new Target(Identifier.from("plain"), "plain"),
				new Target(Identifier.from("a/b"), "slashy"),
				new Target(Identifier.from("100%"), "percenty")
			),
			Catalog.of(
				VERSIONED_1,
				VERSIONED_2
			)
		);
	}

	// Excludes Boot's generated default user, which these tests don't use.
	@Configuration
	@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
	static class TestConfig {
		@Bean
		Bosk<State> bosk() {
			return new Bosk<>(
				boskName(),
				State.class,
				_ -> initialState(),
				BoskConfig.simple()
			);
		}

		@Bean
		ObjectMapper objectMapper(Bosk<State> bosk, JacksonSerializer jacksonSerializer) {
			return JsonMapper.builder()
				.addModule(jacksonSerializer.moduleFor(bosk))
				.build();
		}

		/**
		 * These tests exercise identifiers containing a slash or a percent sign, which are
		 * percent-encoded in the URL. Spring Security's default firewall rejects such URLs,
		 * so allow them here to isolate the bosk path handling under test.
		 */
		@Bean
		HttpFirewall httpFirewall() {
			StrictHttpFirewall firewall = new StrictHttpFirewall();
			firewall.setAllowUrlEncodedSlash(true);
			firewall.setAllowUrlEncodedPercent(true);
			return firewall;
		}
	}

	@BeforeEach
	void resetState() {
		bosk.driver().submitReplacement(bosk.rootReference(), initialState());
	}

	@Test
	void anonymous_isDenied() throws Exception {
		mockMvc.perform(get("/bosk/targets/plain"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void getEntity_plainIdentifier() throws Exception {
		mockMvc.perform(get("/bosk/targets/plain")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(content().string("{\"id\":\"plain\",\"name\":\"plain\"}"));
	}

	@Test
	void getEntity_identifierContainingSlash_percentEncoded() throws Exception {
		// The id "a/b" is one path segment; its slash must be percent-encoded in the URL.
		// Spring must not split it into two segments before Path.parse sees it.
		mockMvc.perform(get("/bosk/targets/a%2Fb")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(content().string("{\"id\":\"a/b\",\"name\":\"slashy\"}"));
	}

	@Test
	void getEntity_identifierContainingPercent() throws Exception {
		// The id "100%" contains a literal percent sign.
		// Spring's URL decoding must not turn it into an invalid percent-escape.
		mockMvc.perform(get("/bosk/targets/100%25")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(content().string("{\"id\":\"100%\",\"name\":\"percenty\"}"));
	}

	@Test
	void getEntity_withRevision_returnsEtag() throws Exception {
		mockMvc.perform(get("/bosk/versioned/v1")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(header().string(ETAG, "\"rev1\""))
			.andExpect(content().json(mapper.writeValueAsString(VERSIONED_1)));
	}

	@Test
	void getEntity_withoutRevision_hasNoEtag() throws Exception {
		mockMvc.perform(get("/bosk/targets/plain")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(ETAG));
	}

	@Test
	void getMissingEntity_hasNoEtag() throws Exception {
		mockMvc.perform(get("/bosk/versioned/missing")
				.with(user("tester")))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist(ETAG));
	}

	@Test
	void getEntity_withAwkwardRevision_escapesEtag() throws Exception {
		mockMvc.perform(get("/bosk/versioned/v2")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(header().string(ETAG, "\"rev%201%25%22%C3%A9\""));
	}

	@Test
	void getWithNoCache_returnsEtagAndNoRetryAfter() throws Exception {
		mockMvc.perform(get("/bosk/versioned/v1")
				.header(CACHE_CONTROL, "no-cache")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(header().string(ETAG, "\"rev1\""))
			.andExpect(header().doesNotExist("Retry-After"));
	}

	@Test
	void putWithMatchingEtag_appliesWrite() throws Exception {
		Versioned updated = new Versioned(Identifier.from("v1"), Identifier.from("rev2"), "updated");
		mockMvc.perform(put("/bosk/versioned/v1")
				.with(user("tester"))
				.with(csrf())
				.contentType(APPLICATION_JSON)
				.header(IF_MATCH, etagFor("/bosk/versioned/v1"))
				.content(mapper.writeValueAsString(updated)))
			.andExpect(status().isAccepted())
			.andExpect(header().doesNotExist(ETAG));
		mockMvc.perform(get("/bosk/versioned/v1")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(header().string(ETAG, "\"rev2\""))
			.andExpect(content().json(mapper.writeValueAsString(updated)));
	}

	@Test
	void putWithStaleEtag_isAcceptedButNoOp() throws Exception {
		Versioned updated = new Versioned(Identifier.from("v1"), Identifier.from("rev2"), "updated");
		mockMvc.perform(put("/bosk/versioned/v1")
				.with(user("tester"))
				.with(csrf())
				.contentType(APPLICATION_JSON)
				.header(IF_MATCH, "\"stale\"")
				.content(mapper.writeValueAsString(updated)))
			.andExpect(status().isAccepted());
		mockMvc.perform(get("/bosk/versioned/v1")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(header().string(ETAG, "\"rev1\""))
			.andExpect(content().json(mapper.writeValueAsString(VERSIONED_1)));
	}

	@Test
	void putWithEscapedEtag_appliesWrite() throws Exception {
		Versioned updated = new Versioned(Identifier.from("v2"), Identifier.from("rev3"), "updated");
		mockMvc.perform(put("/bosk/versioned/v2")
				.with(user("tester"))
				.with(csrf())
				.contentType(APPLICATION_JSON)
				.header(IF_MATCH, etagFor("/bosk/versioned/v2"))
				.content(mapper.writeValueAsString(updated)))
			.andExpect(status().isAccepted());
		mockMvc.perform(get("/bosk/versioned/v2")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(header().string(ETAG, "\"rev3\""));
	}

	@Test
	void putNewEntity_withIfNoneMatch_isAccepted() throws Exception {
		Versioned created = new Versioned(Identifier.from("v3"), Identifier.from("rev1"), "created");
		mockMvc.perform(put("/bosk/versioned/v3")
				.with(user("tester"))
				.with(csrf())
				.contentType(APPLICATION_JSON)
				.header(IF_NONE_MATCH, "*")
				.content(mapper.writeValueAsString(created)))
			.andExpect(status().isAccepted());
		mockMvc.perform(get("/bosk/versioned/v3")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(header().string(ETAG, "\"rev1\""))
			.andExpect(content().json(mapper.writeValueAsString(created)));
	}

	@Test
	void putExistingEntity_withIfNoneMatch_isAcceptedButNoOp() throws Exception {
		Versioned updated = new Versioned(Identifier.from("v1"), Identifier.from("rev2"), "updated");
		mockMvc.perform(put("/bosk/versioned/v1")
				.with(user("tester"))
				.with(csrf())
				.contentType(APPLICATION_JSON)
				.header(IF_NONE_MATCH, "*")
				.content(mapper.writeValueAsString(updated)))
			.andExpect(status().isAccepted());
		mockMvc.perform(get("/bosk/versioned/v1")
				.with(user("tester")))
			.andExpect(status().isOk())
			.andExpect(header().string(ETAG, "\"rev1\""))
			.andExpect(content().json(mapper.writeValueAsString(VERSIONED_1)));
	}

	@Test
	void deleteWithMatchingEtag_deletes() throws Exception {
		mockMvc.perform(delete("/bosk/versioned/v1")
				.with(user("tester"))
				.with(csrf())
				.header(IF_MATCH, etagFor("/bosk/versioned/v1")))
			.andExpect(status().isAccepted());
		mockMvc.perform(get("/bosk/versioned/v1")
				.with(user("tester")))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist(ETAG));
	}

	@Test
	void putWithIfMatch_onNodeWithoutRevision_isBadRequest() throws Exception {
		Target target = new Target(Identifier.from("plain"), "plain");
		mockMvc.perform(put("/bosk/targets/plain")
				.with(user("tester"))
				.with(csrf())
				.contentType(APPLICATION_JSON)
				.header(IF_MATCH, "\"rev1\"")
				.content(mapper.writeValueAsString(target)))
			.andExpect(status().isBadRequest());
	}

	private String etagFor(String path) throws Exception {
		MvcResult result = mockMvc.perform(get(path)
				.with(user("tester")))
			.andExpect(status().isOk())
			.andReturn();
		return result.getResponse().getHeader(ETAG);
	}

	private static final Versioned VERSIONED_1 = new Versioned(Identifier.from("v1"), Identifier.from("rev1"), "value1");
	private static final Versioned VERSIONED_2 = new Versioned(Identifier.from("v2"), Identifier.from("rev 1%\"é"), "value2");
}
