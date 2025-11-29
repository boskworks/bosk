package works.bosk.spring.boot;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import works.bosk.Bosk;
import works.bosk.exceptions.NoReadContextException;

import static org.springframework.http.HttpHeaders.CACHE_CONTROL;

@Component
@ControllerAdvice
@RequiredArgsConstructor
public class ReadContextFilter extends OncePerRequestFilter {
	private final Bosk<?> bosk;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
		if ("no-cache".equalsIgnoreCase(request.getHeader(CACHE_CONTROL))) {
			// Allow the client to specify that they want their read context to have the latest state.
			// We do this even for requests that don't automatically open a read context because
			// they might later manually open one.
			try {
				bosk.driver().flush();
			} catch (InterruptedException e) {
				// Assume the user wanted to interrupt the entire request,
				// not just the flush operation.
				Thread.currentThread().interrupt();
				throw new ServletException(e);
			}
		}
		if (automaticallyOpenReadContext(request)) {
			try (var _ = bosk.readContext()) {
				filterChain.doFilter(request, response);
			}
		} else {
			filterChain.doFilter(request, response);
		}
	}

	/**
	 * The "safe" HTTP methods won't change the server state,
	 * so there's no reason not to open a read context.
	 */
	private boolean automaticallyOpenReadContext(HttpServletRequest request) {
		return switch (request.getMethod()) {
			case "GET", "HEAD", "OPTIONS" -> true;
			default -> false;
		};
	}

	@ExceptionHandler(NoReadContextException.class)
	void handleException(HttpServletRequest request, HttpServletResponse response) throws IOException {
		LOGGER.error("Bosk read context was not opened automatically; the request handler method should open one by calling Bosk.readContext(). " +
			"Request: {} {}", request.getMethod(), request.getRequestURI());
		response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(ReadContextFilter.class);
}
