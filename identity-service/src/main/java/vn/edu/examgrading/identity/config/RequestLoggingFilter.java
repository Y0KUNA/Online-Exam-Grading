package vn.edu.examgrading.identity.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException  {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        requestId = requestId.trim();
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(REQUEST_ID_HEADER, requestId);

        String method = request.getMethod();
        String path = request.getRequestURI();
        long start = System.currentTimeMillis();

        try {
            log.info("Identity request start: method={}, path={}, requestId={}", method, path, requestId);
            filterChain.doFilter(request, response);
            log.info("Identity request completed: method={}, path={}, requestId={}, status={}, durationMs={}",
                method, path, requestId, response.getStatus(), System.currentTimeMillis() - start);
        } catch (Exception ex) {
            log.error("Identity request failed: method={}, path={}, requestId={}, durationMs={}, error={}",
                method, path, requestId, System.currentTimeMillis() - start, ex.getMessage(), ex);
            throw ex;
        } finally {
            MDC.remove(REQUEST_ID_HEADER);
        }
    }
}
