package vn.edu.examgrading.gateway.filter;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Protects public credential endpoints before they reach Identity. */
@Component
public final class LoginRateLimitFilter implements WebFilter, Ordered {
    private final int requestsPerMinute;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();
    public LoginRateLimitFilter(Environment env) { requestsPerMinute = env.getProperty("app.login.rate-limit-per-minute", Integer.class, 10); }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (exchange.getRequest().getMethod() != HttpMethod.POST
            || (!"/api/v1/auth/login".equals(path) && !"/api/v1/auth/register/grader".equals(path))) return chain.filter(exchange);
        long minute = clock.millis() / 60_000L;
        String key = String.valueOf(exchange.getRequest().getRemoteAddress()) + ':' + minute;
        Counter counter = counters.compute(key, (ignored, value) -> value == null || value.minute != minute ? new Counter(minute) : value);
        if (counter.count.incrementAndGet() > requestsPerMinute) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 3; }
    private static final class Counter { private final long minute; private final AtomicInteger count = new AtomicInteger(); private Counter(long minute) { this.minute = minute; } }
}
