package works.bosk.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.StateTreeNode;
import works.bosk.jackson.JacksonSerializer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static works.bosk.testing.BoskTestUtils.boskName;

@SpringBootTest(
	classes = MaintenanceEndpointsTest.TestConfig.class,
	properties = "bosk.web.maintenance-path=/bosk")
@AutoConfigureMockMvc
class MaintenanceEndpointsTest {
	@Autowired
	MockMvc mockMvc;

	public record Target(Identifier id, String name) implements Entity {}

	public record State(Catalog<Target> targets) implements StateTreeNode {}

	@Configuration
	@EnableAutoConfiguration
	static class TestConfig {
		@Bean
		Bosk<State> bosk() {
			return new Bosk<>(
				boskName(),
				State.class,
				_ -> new State(Catalog.of(
					new Target(Identifier.from("plain"), "plain"),
					new Target(Identifier.from("a/b"), "slashy"),
					new Target(Identifier.from("100%"), "percenty")
				)),
				BoskConfig.simple()
			);
		}

		@Bean
		ObjectMapper objectMapper(Bosk<State> bosk, JacksonSerializer jacksonSerializer) {
			return JsonMapper.builder()
				.addModule(jacksonSerializer.moduleFor(bosk))
				.build();
		}
	}

	@Test
	void getEntity_plainIdentifier() throws Exception {
		mockMvc.perform(get("/bosk/targets/plain"))
			.andExpect(status().isOk())
			.andExpect(content().string("{\"id\":\"plain\",\"name\":\"plain\"}"));
	}

	@Test
	void getEntity_identifierContainingSlash_percentEncoded() throws Exception {
		// The id "a/b" is one path segment; its slash must be percent-encoded in the URL.
		// Spring must not split it into two segments before Path.parse sees it.
		mockMvc.perform(get("/bosk/targets/a%2Fb"))
			.andExpect(status().isOk())
			.andExpect(content().string("{\"id\":\"a/b\",\"name\":\"slashy\"}"));
	}

	@Test
	void getEntity_identifierContainingPercent() throws Exception {
		// The id "100%" contains a literal percent sign.
		// Spring's URL decoding must not turn it into an invalid percent-escape.
		mockMvc.perform(get("/bosk/targets/100%25"))
			.andExpect(status().isOk())
			.andExpect(content().string("{\"id\":\"100%\",\"name\":\"percenty\"}"));
	}
}
