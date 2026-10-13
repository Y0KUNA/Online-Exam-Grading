package vn.edu.examgrading.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class StorageAccessFilterTest {
    private final StorageAccessFilter filter = new StorageAccessFilter(50L);

    @Test void unknownBucketIsNotFound() {
        var exchange = exchange(MockServerHttpRequest.get("/storage/other-bucket/file.pdf").build());
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
    }

    @Test void bucketListingAndUnsafeMethodAreRejected() {
        var listing = exchange(MockServerHttpRequest.get("/storage/original").build());
        filter.filter(listing, ignored -> Mono.empty()).block();
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, listing.getResponse().getStatusCode());

        var delete = exchange(MockServerHttpRequest.delete("/storage/anonymized/a").build());
        filter.filter(delete, ignored -> Mono.empty()).block();
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, delete.getResponse().getStatusCode());
    }

    @Test void signedObjectGetIsPassedThroughWithoutApplicationCredentials() {
        String path = "/storage/anonymized/123e4567-e89b-12d3-a456-426614174000/"
            + "123e4567-e89b-12d3-a456-426614174001/v2.pdf?X-Amz-Signature=signature";
        var exchange = exchange(MockServerHttpRequest.get(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer should-not-reach-minio")
            .header(HttpHeaders.COOKIE, "session=secret")
            .header("X-User-Id", "user")
            .build());
        filter.filter(exchange, forwarded -> {
            assertFalse(forwarded.getRequest().getHeaders().containsKey(HttpHeaders.AUTHORIZATION));
            assertFalse(forwarded.getRequest().getHeaders().containsKey(HttpHeaders.COOKIE));
            assertFalse(forwarded.getRequest().getHeaders().containsKey("X-User-Id"));
            assertEquals("private, no-store", forwarded.getResponse().getHeaders().getCacheControl());
            return Mono.empty();
        }).block();
        assertTrue(exchange.getResponse().getHeaders().containsKey("X-Content-Type-Options"));
    }

    @Test void unsignedGetAndOversizedUploadAreRejected() {
        var unsigned = exchange(MockServerHttpRequest.get(
            "/storage/anonymized/123e4567-e89b-12d3-a456-426614174000/123e4567-e89b-12d3-a456-426614174001/v1.pdf").build());
        filter.filter(unsigned, ignored -> Mono.empty()).block();
        assertEquals(HttpStatus.FORBIDDEN, unsigned.getResponse().getStatusCode());

        var oversized = exchange(MockServerHttpRequest.post("/storage/original")
            .contentType(MediaType.MULTIPART_FORM_DATA).header(HttpHeaders.CONTENT_LENGTH, "65587").build());
        filter.filter(oversized, ignored -> Mono.empty()).block();
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, oversized.getResponse().getStatusCode());
    }

    @Test void dangerousObjectOperationQueryIsRejected() {
        var exchange = exchange(MockServerHttpRequest.get("/storage/anonymized/a?list-type=2").build());
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test void unknownBucketWinsOverObjectOperationQuery() {
        var exchange = exchange(MockServerHttpRequest.get("/storage/other?list-type=2").build());
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
    }

    @Test void postWithQueryIsRejectedBeforeMinio() {
        var exchange = exchange(MockServerHttpRequest.post("/storage/original?X-Amz-Signature=bad")
            .contentType(MediaType.MULTIPART_FORM_DATA).build());
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, exchange.getResponse().getStatusCode());
    }

    private MockServerWebExchange exchange(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request);
    }
}
