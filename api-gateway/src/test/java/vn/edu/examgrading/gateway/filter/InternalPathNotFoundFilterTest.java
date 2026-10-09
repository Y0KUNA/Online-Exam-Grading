package vn.edu.examgrading.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class InternalPathNotFoundFilterTest {
    @Test void internalPathReturnsNotFound() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/internal/v1/secret").build());
        new InternalPathNotFoundFilter().filter(exchange, ignored -> Mono.empty()).block();
        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
    }
}
