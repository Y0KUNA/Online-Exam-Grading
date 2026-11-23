package vn.edu.examgrading.submission.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;
import vn.edu.examgrading.submission.dto.SubmissionPageResponse;
import vn.edu.examgrading.submission.dto.UpdatePseudonymRequest;
import vn.edu.examgrading.submission.service.SubmissionServiceMarker;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-level test for the public {@link SubmissionController} surface
 * (TICKET 06): verifies route mapping, query-param binding and delegation.
 * Role enforcement (EXAM_OFFICE) lives at the Gateway — see GatewaySecurityConfigTest.
 */
@ExtendWith(MockitoExtension.class)
class SubmissionControllerTest {

    @Mock
    private SubmissionServiceMarker service;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new SubmissionController(service)).build();
    }

    @Test
    void detail_delegatesToService() throws Exception {
        UUID submissionId = UUID.randomUUID();
        when(service.detail(submissionId)).thenReturn(Map.of("submissionId", submissionId.toString(), "status", "UPLOADED"));

        mvc().perform(get("/api/v1/submissions/{id}", submissionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    void list_bindsStatusCursorLimit() throws Exception {
        UUID examId = UUID.randomUUID();
        when(service.listForExam(eq(examId), eq("FAILED"), eq("cursor-1"), eq(25)))
            .thenReturn(new SubmissionPageResponse(List.of(), "next-1"));

        mvc().perform(get("/api/v1/exams/{examId}/submissions", examId)
                .param("status", "FAILED").param("cursor", "cursor-1").param("limit", "25"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nextCursor").value("next-1"));
    }

    @Test
    void identity_delegatesToService() throws Exception {
        UUID submissionId = UUID.randomUUID();
        when(service.identity(submissionId))
            .thenReturn(Map.of("candidateNumber", "SBD1", "fullName", "A", "pseudonym", "PHACH01"));

        mvc().perform(get("/api/v1/submissions/{id}/identity", submissionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidateNumber").value("SBD1"));
    }

    @Test
    void files_delegatesToService() throws Exception {
        UUID submissionId = UUID.randomUUID();
        when(service.files(submissionId)).thenReturn(Map.of("items", List.of()));

        mvc().perform(get("/api/v1/submissions/{id}/files", submissionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void updatePseudonym_bindsBodyAndAuthorization() throws Exception {
        UUID submissionId = UUID.randomUUID();
        when(service.updatePseudonym(eq(submissionId), any(UpdatePseudonymRequest.class), eq("Bearer tok")))
            .thenReturn(Map.of("submissionId", submissionId.toString(), "pseudonym", "NEW12345"));

        mvc().perform(put("/api/v1/submissions/{id}/pseudonym", submissionId)
                .header("Authorization", "Bearer tok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pseudonym\":\"NEW12345\",\"reason\":\"sai phách\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pseudonym").value("NEW12345"));

        verify(service).updatePseudonym(eq(submissionId),
            argThat((UpdatePseudonymRequest r) -> "NEW12345".equals(r.pseudonym()) && "sai phách".equals(r.reason())),
            eq("Bearer tok"));
    }

    @Test
    void init_delegatesWithAuthorization() throws Exception {
        UUID examId = UUID.randomUUID();
        InitSubmissionResponse response = new InitSubmissionResponse(
            UUID.randomUUID().toString(), "UPLOADING",
            new InitSubmissionResponse.Upload("http://x", Map.of("key", "k")),
            java.time.Instant.now(), "/api/v1/submissions/x");
        when(service.init(eq(examId), any(InitSubmissionRequest.class), isNull(), eq("Bearer tok")))
            .thenReturn(response);

        mvc().perform(post("/api/v1/exams/{examId}/submissions", examId)
                .header("Authorization", "Bearer tok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateId\":\"" + UUID.randomUUID() + "\",\"templateId\":\"" + UUID.randomUUID()
                    + "\",\"fileName\":\"a.pdf\",\"fileSizeBytes\":100,\"contentType\":\"application/pdf\"}"))
            .andExpect(status().isCreated());
    }
}
