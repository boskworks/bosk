package works.bosk.spring.boot;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import works.bosk.Bosk;

@Component
@RequiredArgsConstructor
public class ReadContextFilter implements Filter {
	private final Bosk<?> bosk;

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		try (var __ = bosk.readContext()) {
			chain.doFilter(request, response);
		}
	}
}
