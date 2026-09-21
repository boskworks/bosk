package works.bosk.hello;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal example security.
 * <p>
 * The maintenance endpoints run in {@code BEARER} mode, so they accept only requests that carry
 * a bearer token whose introspection yields the {@code bosk:state} authority. This class stands
 * in for a real application's security configuration: the introspector here simply checks the
 * token against a configured list, whereas a real application would introspect it against an
 * authorization server, or validate a JWT. Nothing else in this example depends on this class.
 * <p>
 * All other endpoints are left open, as they were before this class existed.
 */
@Configuration
class HelloSecurityConfig {
	private final List<String> tokens;

	HelloSecurityConfig(@Value("${example.security.tokens}") String tokens) {
		this.tokens = Arrays.stream(tokens.split(","))
			.map(String::trim)
			.filter(token -> !token.isEmpty())
			.toList();
	}

	/**
	 * The first configured token, so tests can present a valid one.
	 */
	String token() {
		return tokens.get(0);
	}

	@Bean
	OpaqueTokenIntrospector helloTokenIntrospector() {
		return token -> {
			if (!tokens.contains(token)) {
				throw new BadOpaqueTokenException("Unknown token");
			}
			return new DefaultOAuth2AuthenticatedPrincipal(
				"example",
				Map.of("sub", "example"),
				List.of(new SimpleGrantedAuthority("bosk:state")));
		};
	}

	@Bean
	SecurityFilterChain helloSecurityFilterChain(
		HttpSecurity http,
		OpaqueTokenIntrospector introspector
	) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.oauth2ResourceServer(oauth2 -> oauth2.opaqueToken(
				opaque -> opaque.introspector(introspector)));
		return http.build();
	}
}
