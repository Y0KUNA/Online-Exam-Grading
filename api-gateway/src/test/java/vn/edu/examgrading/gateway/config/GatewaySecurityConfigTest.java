package vn.edu.examgrading.gateway.config;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Mono;

/**
 * Role-matrix test for {@link GatewaySecurityConfig} (TICKET 06): the public
 * submission read surface must be EXAM_OFFICE-only (E-06), while graders keep
 * access to exam-scoped reads outside the submissions subtree.
 */
@WebFluxTest(GatewaySecurityConfig.class)
@Import(GatewaySecurityConfig.class)
class GatewaySecurityConfigTest {

    @Autowired
    private WebTestClient webClient;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    private void stubJwt(String role) {
        Jwt jwt = Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .claim("role", role)
            .claim("sub", "user-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.just(jwt));
    }

    private WebTestClient.RequestHeadersSpec<?> get(String uri, String role) {
        stubJwt(role);
        return webClient.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer token");
    }

    @Test
    void submissionDetail_graderForbidden_officeAllowed() {
        String uri = "/api/v1/submissions/" + java.util.UUID.randomUUID();
        get(uri, "GRADER").exchange().expectStatus().isForbidden();
        get(uri, "EXAM_OFFICE").exchange().expectStatus().isNotFound(); // authorized, no route in slice
    }

    @Test
    void submissionIdentity_officeOnly() {
        String uri = "/api/v1/submissions/" + java.util.UUID.randomUUID() + "/identity";
        get(uri, "GRADER").exchange().expectStatus().isForbidden();
        get(uri, "COUNCIL").exchange().expectStatus().isForbidden();
        get(uri, "EXAM_OFFICE").exchange().expectStatus().isNotFound();
    }

    @Test
    void submissionFiles_andPseudonymPut_officeOnly() {
        String base = "/api/v1/submissions/" + java.util.UUID.randomUUID();
        get(base + "/files", "GRADER").exchange().expectStatus().isForbidden();
        get(base + "/files", "EXAM_OFFICE").exchange().expectStatus().isNotFound();
        stubJwt("GRADER");
        webClient.put().uri(base + "/pseudonym")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange().expectStatus().isForbidden();
        stubJwt("EXAM_OFFICE");
        webClient.put().uri(base + "/pseudonym")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void examSubmissionsList_andSse_officeOnly() {
        String base = "/api/v1/exams/" + java.util.UUID.randomUUID() + "/submissions";
        get(base, "GRADER").exchange().expectStatus().isForbidden();
        get(base, "COUNCIL").exchange().expectStatus().isForbidden();
        get(base, "EXAM_OFFICE").exchange().expectStatus().isNotFound();
        get(base + "/events", "GRADER").exchange().expectStatus().isForbidden();
        get(base + "/events", "EXAM_OFFICE").exchange().expectStatus().isNotFound();
    }

    @Test
    void examReads_stillAllowGraderAndCouncil() {
        // Regression guard: only the submissions subtree was tightened.
        get("/api/v1/exams", "GRADER").exchange().expectStatus().isNotFound();
        get("/api/v1/exams", "COUNCIL").exchange().expectStatus().isNotFound();
    }

    @Test
    void unauthenticated_isUnauthorized() {
        webClient.get().uri("/api/v1/submissions/" + java.util.UUID.randomUUID())
            .exchange().expectStatus().isUnauthorized();
    }
}
