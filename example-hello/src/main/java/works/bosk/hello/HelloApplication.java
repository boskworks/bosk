package works.bosk.hello;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import works.bosk.logback.BoskLogFilter;

@SpringBootApplication
public class HelloApplication {

	static void main(String[] args) {
		SpringApplication.run(HelloApplication.class, args);
	}

	@Bean
	BoskLogFilter.LogController logController() {
		return new BoskLogFilter.LogController();
	}
}
