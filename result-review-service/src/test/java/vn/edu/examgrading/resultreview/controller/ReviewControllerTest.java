package vn.edu.examgrading.resultreview.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.resultreview.client.PurgeClient;

@ExtendWith(MockitoExtension.class)
class ReviewControllerTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private PurgeClient submissionClient;
    private ReviewController controller;
    private final UUID reviewId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new ReviewController(jdbc, submissionClient);
    }

    private void stubReview(String reasonType, String techCode, String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("submission_id", submissionId);
        row.put("reason_type", reasonType);
        row.put("technical_code", techCode);
        row.put("status", status);
        when(jdbc.queryForList(contains("from review where review_id=?"), eq(reviewId)))
            .thenReturn(List.of(row));
    }

    @Test
    void dismissMaskInsufficient_resolvesAndResumesGrading_t28() {
        stubReview("ANONYMIZATION", "MASK_INSUFFICIENT", "OPEN");
        Map<String, Object> res = controller.decide(reviewId,
            Map.of("decisionType", "DISMISS", "reason", "vùng che đủ dùng"));

        assertEquals("RESOLVED", res.get("status"));
        // T28: submission returns to READY_FOR_GRADING BEFORE the review closes
        verify(submissionClient).resumeGrading(submissionId);
        verify(jdbc).update(contains("resolution_type=?"), eq("DISMISS"), any(), eq(reviewId));
        verify(jdbc, never()).update(contains("authorized_action=?"), any(), any());
    }

    @Test
    void reprocessMaskInsufficient_authorizesAction() {
        stubReview("ANONYMIZATION", "MASK_INSUFFICIENT", "OPEN");
        Map<String, Object> res = controller.decide(reviewId,
            Map.of("decisionType", "REPROCESS", "reason", "che lại"));

        assertEquals("ACTION_AUTHORIZED", res.get("status"));
        verify(submissionClient, never()).resumeGrading(any());
        verify(jdbc).update(contains("authorized_action=?"), eq("REPROCESS"), eq(reviewId));
    }

    @Test
    void decisionNotAllowed_forWrongType() {
        stubReview("ANONYMIZATION", "TEMPLATE_SIZE_MISMATCH", "OPEN");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.decide(reviewId,
                Map.of("decisionType", "DISMISS", "reason", "x")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("DECISION_NOT_ALLOWED", ex.getReason());
    }

    @Test
    void alreadyDecided() {
        stubReview("ANONYMIZATION", "MASK_INSUFFICIENT", "RESOLVED");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.decide(reviewId,
                Map.of("decisionType", "DISMISS", "reason", "x")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("REVIEW_ALREADY_DECIDED", ex.getReason());
    }

    @Test
    void blankReason_422() {
        // reason is validated before any repository lookup
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.decide(reviewId, Map.of("decisionType", "DISMISS", "reason", " ")));
        assertEquals("REASON_REQUIRED", ex.getReason());
    }

    @Test
    void setFinalScore_resolvesWithoutResume() {
        stubReview("SCORE_DIFFERENCE", "", "OPEN");
        Map<String, Object> res = controller.decide(reviewId,
            Map.of("decisionType", "SET_FINAL_SCORE", "reason", "chốt điểm", "finalScore", "8.5"));
        assertEquals("RESOLVED", res.get("status"));
        verify(submissionClient, never()).resumeGrading(any());
    }
}
