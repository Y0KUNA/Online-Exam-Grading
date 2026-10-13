package vn.edu.examgrading.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class CorrelationIdFilterTest {
    @Test void invalidIdIsReplacedWithUuid() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("X-Request-Id", "bad").build());
        new CorrelationIdFilter().filter(exchange, request -> {
            String id = request.getRequest().getHeaders().getFirst("X-Request-Id");
            assertDoesNotThrow(() -> java.util.UUID.fromString(id));
            return Mono.empty();
        }).block();
    }

    @Test void validIdIsPreservedOnResponse() {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("X-Request-Id", id).build());
        new CorrelationIdFilter().filter(exchange, request -> Mono.empty()).block();
        assertEquals(id, exchange.getResponse().getHeaders().getFirst("X-Request-Id"));
    }
}
