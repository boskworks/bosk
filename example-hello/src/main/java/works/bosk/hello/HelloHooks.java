package works.bosk.hello;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import works.bosk.Reference;
import works.bosk.annotations.Hook;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.hello.state.Target;

@Component
public class HelloHooks {
	public HelloHooks(HelloBosk bosk) throws InvalidTypeException {
		bosk.registerHooks(this);
	}

	@Hook("/targets/-target-")
	void targetChanged(Reference<Target> ref) {
		if (ref.exists()) {
			LOGGER.info("Target: {}", ref.value());
		} else {
			LOGGER.info("Target removed: {}", ref);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(HelloHooks.class);
}
