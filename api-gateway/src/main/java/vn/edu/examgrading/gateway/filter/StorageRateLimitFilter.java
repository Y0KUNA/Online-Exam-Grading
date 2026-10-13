package vn.edu.examgrading.gateway.filter;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Small, dependency-free protective limit for presigned URLs.  Production deployments
 * should keep the gateway instances behind a shared edge rate limiter as well. */
@Component
public final class StorageRateLimitFilter implements WebFilter, Ordered {
    private final int requestsPerMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public StorageRateLimitFilter(Environment environment) {
        this(environment.getProperty("app.storage.rate-limit-per-minute", Integer.class, 120), Clock.systemUTC());
    }

    StorageRateLimitFilter(int requestsPerMinute, Clock clock) {
        this.requestsPerMinute = requestsPerMinute;
        this.clock = clock;
    }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/storage/")) return chain.filter(exchange);
        long minute = clock.millis() / 60_000L;
        String client = String.valueOf(exchange.getRequest().getRemoteAddress()) + ':' + minute;
        Window window = windows.compute(client, (ignored, existing) ->
            existing == null || existing.minute != minute ? new Window(minute) : existing);
        if (window.count.incrementAndGet() > requestsPerMinute) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 2; }
    private static final class Window {
        private final long minute; private final AtomicInteger count = new AtomicInteger();
        private Window(long minute) { this.minute = minute; }
    }
}
