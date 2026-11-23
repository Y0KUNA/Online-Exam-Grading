package vn.edu.examgrading.resultreview.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import vn.edu.examgrading.common.security.HmacSigner;

/**
 * Unit tests for {@link PurgeClient} (§9.3): correct URLs, HMAC service
 * signature headers, Idempotency-Key, 404→DONE tolerance, error propagation.
 */
@ExtendWith(MockitoExtension.class)
class PurgeClientTest {

    private static final String SECRET = "test-hmac-secret";

    @Mock private RestTemplate restTemplate;

    private PurgeClient client;
    private final UUID resultId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        client = new PurgeClient(restTemplate, SECRET,
            "http://submission:8082/", "http://grading:8083", "http://identity:8081");
        lenient().when(restTemplate.exchange(any(URI.class), any(HttpMethod.class),
            any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"status\":\"DONE\"}"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private HttpHeaders lastHeaders() {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, atLeastOnce()).exchange(any(URI.class), any(HttpMethod.class),
            captor.capture(), eq(String.class));
        return captor.getValue().getHeaders();
    }

    private URI lastUrl() {
        ArgumentCaptor<URI> captor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate, atLeastOnce()).exchange(captor.capture(), any(HttpMethod.class),
            any(HttpEntity.class), eq(String.class));
        return captor.getValue();
    }

    @Test
    void purgeSubmission_callsCorrectUrlWithSignedHeadersAndIdempotencyKey() {
        client.purgeSubmission(resultId, submissionId);

        assertEquals(URI.create("http://submission:8082/internal/v1/submissions/"
            + submissionId + "/detail"), lastUrl());
        HttpHeaders h = lastHeaders();
        assertEquals("result-review-service", h.getFirst("X-Service-Caller"));
        assertEquals(resultId + ":retention:v1", h.getFirst("Idempotency-Key"));
        // signature must verify against the canonical payload
        String requestId = h.getFirst("X-Service-Request-Id");
        Instant timestamp = Instant.parse(h.getFirst("X-Service-Timestamp"));
        String expected = HmacSigner.sign(SECRET,
            "result-review-service\n" + requestId + "\n" + timestamp.toEpochMilli());
        assertEquals(expected, h.getFirst("X-Service-Signature"));
    }

    @Test
    void purgeGrading_andIdentity_useTheirOwnPaths() {
        client.purgeGrading(resultId, submissionId);
        client.purgeIdentity(resultId, submissionId);

        verify(restTemplate).exchange(
            eq(URI.create("http://grading:8083/internal/v1/grading/submissions/"
                + submissionId + "/detail")), eq(HttpMethod.DELETE), any(), eq(String.class));
        verify(restTemplate).exchange(
            eq(URI.create("http://identity:8081/internal/v1/identity/submissions/"
                + submissionId + "/pseudonym")), eq(HttpMethod.DELETE), any(), eq(String.class));
    }

    @Test
    void notFound_isTreatedAsDone() {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class),
            any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "nf", null, null, null));

        assertDoesNotThrow(() -> client.purgeSubmission(resultId, submissionId));
    }

    @Test
    void serverError_propagates() {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class),
            any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "err", null, null, null));

        assertThrows(HttpClientErrorException.class,
            () -> client.purgeIdentity(resultId, submissionId));
    }
}
