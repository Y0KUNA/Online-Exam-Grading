package vn.edu.examgrading.gateway.filter;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class RequestLoggingFilter implements WebFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String requestId = request.getHeaders().getFirst(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(REQUEST_ID_HEADER, requestId);

        String path = request.getURI().getPath();
        String method = request.getMethod() == null ? "UNKNOWN" : request.getMethod().name();
        long start = System.currentTimeMillis();
        final String finalMethod = method;
        final String finalPath = path;
        final String finalRequestId = requestId;
        final long finalStart = start;

        log.info("Gateway request start: method={}, path={}, requestId={}", finalMethod, finalPath, finalRequestId);

        return chain.filter(exchange)
            .doOnSuccess(signal -> {
                var status = exchange.getResponse().getStatusCode();
                long duration = System.currentTimeMillis() - finalStart;
                if (status != null && status.is5xxServerError()) {
                    log.error("Gateway request completed with server error: method={}, path={}, requestId={}, status={}, durationMs={}",
                        finalMethod, finalPath, finalRequestId, status.value(), duration);
                } else {
                    log.info("Gateway request completed: method={}, path={}, requestId={}, status={}, durationMs={}",
                        finalMethod, finalPath, finalRequestId, status != null ? status.value() : "UNKNOWN", duration);
                }
            })
            .doOnError(error -> log.error("Gateway request failed: method={}, path={}, requestId={}, durationMs={}, error={}",
                finalMethod, finalPath, finalRequestId, System.currentTimeMillis() - finalStart, error.getMessage(), error))
            .doFinally(signal -> MDC.remove(REQUEST_ID_HEADER));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
