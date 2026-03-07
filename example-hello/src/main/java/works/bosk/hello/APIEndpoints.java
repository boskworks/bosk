package works.bosk.hello;

import java.util.Collection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public record APIEndpoints (
	HelloBosk bosk
){
	@GetMapping("/hello")
	GreetingDTO getHello() {
		return new GreetingDTO(
			bosk.refs.targets().value().stream()
				.map(t -> "Hello, " + t.id() + "!")
				.toList());
	}

	@GetMapping("/targets")
	Object getTargets() {
		return bosk.refs.targets().value();
	}

	/**
	 * This should throw a 500. Useful for testing.
	 */
	@PostMapping("/noReadSession")
	void noReadSession() {
		bosk.rootReference().value();
	}

	@GetMapping("/hookRegistrations")
	Collection<HelloBosk.HookRegistration<?>> getHookRegistrations() {
		return bosk.allRegisteredHooks();
	}
}
