package vn.edu.examgrading.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** MinIO operational endpoints are never part of the public Gateway surface. */
@Component
public final class MinioPathNotFoundFilter implements WebFilter, Ordered {
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/minio/")) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
