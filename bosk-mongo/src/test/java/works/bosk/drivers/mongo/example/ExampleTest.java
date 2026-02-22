package works.bosk.drivers.mongo.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import works.bosk.exceptions.InvalidTypeException;

@Disabled("Not yet actually hooked up to MongoDB in Testcontainers")
public class ExampleTest {
	ExampleBosk bosk;

	@BeforeEach
	void setupBosk() throws InvalidTypeException {
		bosk = new ExampleBosk();
	}

	@Test
	void readSession() {
		try (var _ = bosk.readSession()) {
			System.out.println("Hello, " + bosk.refs.name().value());
		}
	}

	@Test
	void driverUpdate() {
		bosk.driver().submitReplacement(bosk.refs.name(), "everybody");
	}

	@Test
	void hook() {
		bosk.hookRegistrar().registerHook("Greetings", bosk.refs.name(), ref -> {
			System.out.println("Name is now: " + ref.value());
		});
	}

}
