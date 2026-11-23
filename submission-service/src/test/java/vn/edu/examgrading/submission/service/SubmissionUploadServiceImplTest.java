package vn.edu.examgrading.submission.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionError;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.dto.CompleteSubmissionResponse;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;
import vn.edu.examgrading.submission.dto.SubmissionIdentity;
import vn.edu.examgrading.submission.dto.SubmissionPageResponse;
import vn.edu.examgrading.submission.dto.UpdatePseudonymRequest;
import vn.edu.examgrading.submission.messaging.ValidationWorker;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.InMemoryObjectStorage;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.config.StorageProperties;
import vn.edu.examgrading.submission.client.IdentityClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link SubmissionUploadServiceImpl} (TICKET 04).
 * Mocks repositories and uses InMemoryObjectStorage (STORAGE_MODE=memory) to run
 * without Docker. Covers 422/404/409/201 response shapes.
 */
@ExtendWith(MockitoExtension.class)
class SubmissionUploadServiceImplTest {

    @Mock
    private SubmissionRepositoryMarker submissionRepo;

    @Mock
    private PaperTemplateRepository templateRepo;

    @Mock
    private SubmissionEventRepository eventRepo;

    @Mock
    private ExamFileRepository examFileRepo;

    @Mock
    private ProcessingJobRepository processingJobRepo;

    @Mock
    private IdentityClient identityClient;

    @Mock
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private ObjectStorageGateway gateway;
    private StorageProperties props;
    private Clock clock;
    private SubmissionUploadServiceImpl service;

    private final UUID examId = UUID.randomUUID();
    private final UUID candidateId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        gateway = new InMemoryObjectStorage();
        props = new StorageProperties(
            "memory",
            "http://localhost:9000",
            "us-east-1",
            "minioadmin",
            "change-me",
            "original",
            "anonymized",
            "http://localhost:8080/storage",
            52428800L,
            1024L
        );
        clock = Clock.systemUTC();
        service = new SubmissionUploadServiceImpl(
            submissionRepo, templateRepo, eventRepo, examFileRepo, processingJobRepo,
            gateway, props, clock, identityClient, rabbitTemplate, jdbcTemplate, 10);
    }

    @Test
    void init_withValidRequest_returnsUploadTicket() {
        when(templateRepo.existsActive(templateId)).thenReturn(true);

        InitSubmissionRequest request = new InitSubmissionRequest(
            candidateId, templateId, "test.pdf", 52428800L, "application/pdf");
        String authorization = "Bearer test-user-jwt";

        InitSubmissionResponse response = service.init(examId, request, actorId, authorization);

        assertNotNull(response);
        assertEquals("UPLOADING", response.status());
        assertNotNull(response.upload());
        assertNotNull(response.upload().url());
        assertNotNull(response.upload().fields());
        assertTrue(response.upload().fields().containsKey("key"));
        assertNotNull(response.uploadExpiresAt());

        verify(submissionRepo).insertUploading(any(UUID.class), eq(examId),
            eq(templateId), any(Instant.class), eq(52428800L), any(String.class));
        verify(identityClient).createBinding(eq(examId), any(UUID.class), eq(candidateId), eq(authorization));
        verify(eventRepo).append(any(UUID.class), eq(SubmissionStatus.UPLOADING), eq(0));
    }

    @Test
    void init_withUnsupportedContentType_throws422() {
        InitSubmissionRequest request = new InitSubmissionRequest(
            candidateId, templateId, "test.jpg", 52428800L, "image/jpeg");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.init(examId, request, actorId));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals(SubmissionError.UNSUPPORTED_CONTENT_TYPE, ex.getReason());

        verify(submissionRepo, never()).insertUploading(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void init_withInvalidFileSizeZero_throws422() {
        InitSubmissionRequest request = new InitSubmissionRequest(
            candidateId, templateId, "test.pdf", 0L, "application/pdf");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.init(examId, request, actorId));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals(SubmissionError.INVALID_FILE_SIZE, ex.getReason());
    }

    @Test
    void init_withFileSizeExceedsMax_throws422() {
        InitSubmissionRequest request = new InitSubmissionRequest(
            candidateId, templateId, "test.pdf", 52428801L, "application/pdf");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.init(examId, request, actorId));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals(SubmissionError.INVALID_FILE_SIZE, ex.getReason());
    }

    @Test
    void init_withTemplateNotFound_throws404() {
        when(templateRepo.existsActive(templateId)).thenReturn(false);

        InitSubmissionRequest request = new InitSubmissionRequest(
            candidateId, templateId, "test.pdf", 52428800L, "application/pdf");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.init(examId, request, actorId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals(SubmissionError.TEMPLATE_NOT_FOUND, ex.getReason());
    }

    @Test
    void init_withDuplicateKey_throwsException() {
        when(templateRepo.existsActive(templateId)).thenReturn(true);
        doThrow(new DuplicateKeyException("duplicate key")).when(submissionRepo)
            .insertUploading(any(UUID.class), eq(examId), eq(templateId),
                any(Instant.class), anyLong(), any(String.class));

        InitSubmissionRequest request = new InitSubmissionRequest(
            candidateId, templateId, "test.pdf", 52428800L, "application/pdf");

        assertThrows(DuplicateKeyException.class,
            () -> service.init(examId, request, actorId));
    }

    @Test
    void init_withCandidateAlreadyBound_throws409AndRollsBack() {
        when(templateRepo.existsActive(templateId)).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "CANDIDATE_ALREADY_BOUND"))
            .when(identityClient).createBinding(eq(examId), any(UUID.class), eq(candidateId), eq("Bearer test-user-jwt"));

        InitSubmissionRequest request = new InitSubmissionRequest(
            candidateId, templateId, "test.pdf", 52428800L, "application/pdf");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.init(examId, request, actorId, "Bearer test-user-jwt"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(submissionRepo).deleteById(any(UUID.class));
    }

    @Test
    void init_withCandidateExamMismatch_throws409AndRollsBack() {
        when(templateRepo.existsActive(templateId)).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "CANDIDATE_EXAM_MISMATCH"))
            .when(identityClient).createBinding(eq(examId), any(UUID.class), eq(candidateId), eq("Bearer test-user-jwt"));

        InitSubmissionRequest request = new InitSubmissionRequest(
            candidateId, templateId, "test.pdf", 52428800L, "application/pdf");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.init(examId, request, actorId, "Bearer test-user-jwt"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(submissionRepo).deleteById(any(UUID.class));
    }

    @Test
    void createBinding_forwardsAuthorizationHeader() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        IdentityClient client = new IdentityClient(restTemplate, "http://localhost:8081");
        UUID requestExamId = UUID.randomUUID();
        UUID requestSubmissionId = UUID.randomUUID();
        UUID requestCandidateId = UUID.randomUUID();
        String authorization = "Bearer forwarded-user-jwt";

        client.createBinding(requestExamId, requestSubmissionId, requestCandidateId, authorization);

        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
            eq("http://localhost:8081/internal/v1/submission-bindings"),
            eq(HttpMethod.POST),
            entityCaptor.capture(),
            eq(Void.class));
        assertEquals(authorization, entityCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void listForExam_returnsSubmissions() {
        when(submissionRepo.findByExamPage(eq(examId), isNull(), isNull(), isNull(), eq(51)))
            .thenReturn(List.of());

        SubmissionPageResponse result = service.listForExam(examId, null, null, null);

        assertNotNull(result);
        assertEquals(0, result.items().size());
        assertNull(result.nextCursor());
    }

    private Submission submissionRow(UUID id, SubmissionStatus status, Instant updatedAt) {
        return new Submission(id, examId, templateId, status,
            1, 10, null, 100L, 95L, null, "key/" + id + ".pdf", null, 0, updatedAt);
    }

    @Test
    void listForExam_filtersStatusAndClampsLimit() {
        UUID id = UUID.randomUUID();
        when(submissionRepo.findByExamPage(eq(examId), eq(SubmissionStatus.FAILED),
            isNull(), isNull(), eq(201))).
            thenReturn(List.of(submissionRow(id, SubmissionStatus.FAILED, Instant.now())));

        SubmissionPageResponse result = service.listForExam(examId, "failed", null, 500);

        assertEquals(1, result.items().size());
        assertEquals("FAILED", result.items().get(0).get("status"));
        assertNull(result.nextCursor());
    }

    @Test
    void listForExam_paginatesWithNextCursor() {
        Instant base = Instant.parse("2026-11-23T10:00:00.123456Z");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        // Repo returns limit+1 rows; service must trim and emit a cursor for row 2.
        when(submissionRepo.findByExamPage(eq(examId), isNull(), isNull(), isNull(), eq(3)))
            .thenReturn(List.of(
                submissionRow(first, SubmissionStatus.UPLOADED, base),
                submissionRow(second, SubmissionStatus.UPLOADED, base.minusSeconds(1)),
                submissionRow(third, SubmissionStatus.UPLOADED, base.minusSeconds(2))));

        SubmissionPageResponse page1 = service.listForExam(examId, null, null, 2);

        assertEquals(2, page1.items().size());
        assertNotNull(page1.nextCursor());
        assertEquals(first.toString(), page1.items().get(0).get("submissionId").toString());

        // Second page: cursor decodes back to (updatedAt, submissionId) of last item.
        when(submissionRepo.findByExamPage(eq(examId), isNull(), eq(base.minusSeconds(1)),
            eq(second), eq(3)))
            .thenReturn(List.of(submissionRow(third, SubmissionStatus.UPLOADED, base.minusSeconds(2))));

        SubmissionPageResponse page2 = service.listForExam(examId, null, page1.nextCursor(), 2);

        assertEquals(1, page2.items().size());
        assertEquals(third.toString(), page2.items().get(0).get("submissionId").toString());
        assertNull(page2.nextCursor());
    }

    @Test
    void listForExam_invalidParams_throw400() {
        assertThrows(ResponseStatusException.class,
            () -> service.listForExam(examId, "NOT_A_STATUS", null, null));
        assertThrows(ResponseStatusException.class,
            () -> service.listForExam(examId, null, "!!!not-base64!!!", null));
        assertThrows(ResponseStatusException.class,
            () -> service.listForExam(examId, null, null, 0));
    }

    @Test
    void detail_notFound_throws404() {
        UUID submissionId = UUID.randomUUID();
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.detail(submissionId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals(SubmissionError.INVALID_STATE, ex.getReason());
    }

    @Test
    void detail_returnsSpecContractShape() {
        UUID submissionId = UUID.randomUUID();
        Instant updatedAt = Instant.now();
        Submission submission = new Submission(
            submissionId, examId, templateId, SubmissionStatus.FAILED,
            1, 40, null, 100L, 95L, 4, "internal/key.pdf", "PDF_CORRUPT", 3, updatedAt);
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));

        Map<String, Object> result = service.detail(submissionId);

        // Spec: {submissionId,examId,status,pageCount?,progress,error?:{code,message},updatedAt}
        assertEquals(submissionId.toString(), result.get("submissionId").toString());
        assertEquals(examId.toString(), result.get("examId").toString());
        assertEquals("FAILED", result.get("status"));
        assertEquals(4, result.get("pageCount"));
        assertEquals(40, result.get("progress"));
        assertEquals(updatedAt, result.get("updatedAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) result.get("error");
        assertEquals("PDF_CORRUPT", error.get("code"));
        assertEquals("PDF_CORRUPT", error.get("message"));
        // Internal fields must not leak into the public projection.
        assertFalse(result.containsKey("objectKey"));
        assertFalse(result.containsKey("templateId"));
        assertFalse(result.containsKey("failureCode"));
        assertFalse(result.containsKey("version"));
    }

    @Test
    void detail_withoutPageCountOrError_omitsOptionalFields() {
        UUID submissionId = UUID.randomUUID();
        Submission submission = new Submission(
            submissionId, examId, templateId, SubmissionStatus.UPLOADING,
            1, 0, Instant.now().plusSeconds(600),
            100L, null, null, "key", null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));

        Map<String, Object> result = service.detail(submissionId);

        assertEquals("UPLOADING", result.get("status"));
        assertFalse(result.containsKey("pageCount"));
        assertFalse(result.containsKey("error"));
        assertTrue(result.containsKey("progress"));
        assertTrue(result.containsKey("updatedAt"));
    }

    @Test
    void identity_returnsPublicShapeAndAudits() {
        UUID submissionId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        Submission submission = new Submission(
            submissionId, examId, templateId, SubmissionStatus.READY_FOR_GRADING,
            1, 100, null, 100L, 95L, null, "key", null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));
        when(identityClient.getSubmissionIdentity(submissionId)).thenReturn(
            new SubmissionIdentity(candidateId, "SBD001", "Nguyen Van A", "6A1", "KN5X5XUH"));

        Map<String, Object> result = service.identity(submissionId);

        assertEquals("SBD001", result.get("candidateNumber"));
        assertEquals("Nguyen Van A", result.get("fullName"));
        assertEquals("6A1", result.get("className"));
        assertEquals("KN5X5XUH", result.get("pseudonym"));
        assertFalse(result.containsKey("candidateId"));
        verify(jdbcTemplate).update(contains("SUBMISSION_IDENTITY_VIEWED"),
            any(), eq(submissionId), anyString());
    }

    @Test
    void identity_submissionNotFound_throws404() {
        UUID submissionId = UUID.randomUUID();
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.identity(submissionId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verifyNoInteractions(identityClient);
    }

    @Test
    void identity_bindingNotFound_mapsTo404() {
        UUID submissionId = UUID.randomUUID();
        Submission submission = new Submission(
            submissionId, examId, templateId, SubmissionStatus.UPLOADED,
            1, 0, null, 100L, null, null, "key", null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));
        when(identityClient.getSubmissionIdentity(submissionId)).thenThrow(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "{\"code\":\"BINDING_NOT_FOUND\"}"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.identity(submissionId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), anyString());
    }

    private Submission readySubmission(UUID submissionId, SubmissionStatus status) {
        return new Submission(submissionId, examId, templateId, status,
            1, 100, null, 100L, 95L, null, "key", null, 0, Instant.now());
    }

    private static String bearerJwtWithSub(UUID userId) {
        String payload = "{\"sub\":\"" + userId + "\"}";
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "Bearer h." + encoded + ".s";
    }

    @Test
    void updatePseudonym_success_callsIdentityWithActorAndAudits() {
        UUID submissionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(submissionRepo.findById(submissionId))
            .thenReturn(Optional.of(readySubmission(submissionId, SubmissionStatus.COMPLETED)));

        Map<String, Object> result = service.updatePseudonym(submissionId,
            new UpdatePseudonymRequest("NEW12345", "sai phách"), bearerJwtWithSub(actorId));

        assertEquals(submissionId.toString(), result.get("submissionId").toString());
        assertEquals("NEW12345", result.get("pseudonym"));
        verify(identityClient).updatePseudonym(submissionId, "NEW12345", "sai phách", actorId.toString());
        verify(jdbcTemplate).update(contains("SUBMISSION_PSEUDONYM_CHANGED"),
            any(), eq(submissionId), anyString());
    }

    @Test
    void updatePseudonym_finalized_throws409() {
        UUID submissionId = UUID.randomUUID();
        when(submissionRepo.findById(submissionId))
            .thenReturn(Optional.of(readySubmission(submissionId, SubmissionStatus.FINALIZED)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.updatePseudonym(submissionId,
                new UpdatePseudonymRequest("NEW12345", "reason"), null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verifyNoInteractions(identityClient);
    }

    @Test
    void updatePseudonym_missingReason_throws422() {
        UUID submissionId = UUID.randomUUID();
        when(submissionRepo.findById(submissionId))
            .thenReturn(Optional.of(readySubmission(submissionId, SubmissionStatus.COMPLETED)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.updatePseudonym(submissionId,
                new UpdatePseudonymRequest("NEW12345", " "), null));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("REASON_REQUIRED", ex.getReason());
        verifyNoInteractions(identityClient);
    }

    @Test
    void updatePseudonym_noPseudonymYet_maps404To409() {
        UUID submissionId = UUID.randomUUID();
        when(submissionRepo.findById(submissionId))
            .thenReturn(Optional.of(readySubmission(submissionId, SubmissionStatus.UPLOADED)));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "PSEUDONYM_NOT_FOUND"))
            .when(identityClient).updatePseudonym(eq(submissionId), any(), any(), any());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.updatePseudonym(submissionId,
                new UpdatePseudonymRequest("NEW12345", "reason"), null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("INVALID_STATE", ex.getReason());
    }

    @Test
    void updatePseudonym_valueTaken_propagates409() {
        UUID submissionId = UUID.randomUUID();
        when(submissionRepo.findById(submissionId))
            .thenReturn(Optional.of(readySubmission(submissionId, SubmissionStatus.COMPLETED)));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "PSEUDONYM_TAKEN"))
            .when(identityClient).updatePseudonym(eq(submissionId), any(), any(), any());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.updatePseudonym(submissionId,
                new UpdatePseudonymRequest("TAKEN999", "reason"), null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void files_returnsVersionsWithoutObjectKey() {
        UUID submissionId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        when(submissionRepo.findById(submissionId))
            .thenReturn(Optional.of(readySubmission(submissionId, SubmissionStatus.READY_FOR_GRADING)));
        when(examFileRepo.findAllBySubmission(submissionId)).thenReturn(List.of(
            new vn.edu.examgrading.submission.repository.ExamFileRepository.ExamFileVersion(
                "ANONYMIZED", 1, true, 2048L, "abc123", createdAt),
            new vn.edu.examgrading.submission.repository.ExamFileRepository.ExamFileVersion(
                "ORIGINAL", 2, true, 1900L, null, createdAt),
            new vn.edu.examgrading.submission.repository.ExamFileRepository.ExamFileVersion(
                "ORIGINAL", 1, false, 1800L, null, createdAt.minusSeconds(60))));

        Map<String, Object> result = service.files(submissionId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(3, items.size());
        Map<String, Object> anonymized = items.get(0);
        assertEquals("ANONYMIZED", anonymized.get("type"));
        assertEquals(1, anonymized.get("version"));
        assertEquals(true, anonymized.get("isCurrent"));
        assertEquals(2048L, anonymized.get("sizeBytes"));
        assertEquals("abc123", anonymized.get("sha256"));
        assertEquals(createdAt, anonymized.get("createdAt"));
        assertFalse(anonymized.containsKey("objectKey"));
        assertFalse(anonymized.containsKey("object_key"));
        // sha256 optional: ORIGINAL v1 has none → key absent.
        assertFalse(items.get(2).containsKey("sha256"));
    }

    @Test
    void files_submissionNotFound_throws404() {
        UUID submissionId = UUID.randomUUID();
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.files(submissionId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void complete_withUploadedFile_publishesFullValidateMessage() {
        UUID submissionId = UUID.randomUUID();
        String objectKey = "exams/" + examId + "/" + submissionId + "/v1.pdf";
        Submission submission = new Submission(
            submissionId, examId, templateId, SubmissionStatus.UPLOADING,
            1, 0, Instant.now().plusSeconds(600),
            100L, null, null, objectKey, null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));
        when(examFileRepo.existsCurrentOriginal(submissionId)).thenReturn(false);
        when(submissionRepo.transition(submissionId, SubmissionStatus.UPLOADING,
            SubmissionStatus.UPLOADED, 0)).thenReturn(1);
        gateway.put(objectKey, new byte[100], "application/pdf");

        CompleteSubmissionResponse response = service.complete(submissionId);

        assertEquals(submissionId.toString(), response.submissionId());
        assertEquals("UPLOADED", response.status());
        verify(processingJobRepo).insertQueued(any(UUID.class), eq(submissionId), eq("VALIDATE"), eq(1), eq(templateId));
        // §8: message must carry the full schema — no pseudonym/candidateId.
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("validate"),
            argThat((Object msg) -> msg instanceof ValidationWorker.ValidateJobMessage m
                && m.schemaVersion() == ValidationWorker.ValidateJobMessage.SCHEMA_VERSION
                && m.submissionId().equals(submissionId)
                && m.examId().equals(examId)
                && m.templateId().equals(templateId)
                && m.fileVersion() == 1
                && !m.force()
                && m.attempt() == 0
                && m.correlationId() != null));
        verify(eventRepo).append(submissionId, SubmissionStatus.UPLOADED, 0);
    }

    @Test
    void complete_whenAlreadyUploaded_isIdempotent() {
        UUID submissionId = UUID.randomUUID();
        Submission submission = new Submission(
            submissionId, examId, templateId, SubmissionStatus.UPLOADED,
            1, 0, null, 100L, null, null, "key", null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));

        CompleteSubmissionResponse response = service.complete(submissionId);

        assertEquals("UPLOADED", response.status());
        verifyNoInteractions(rabbitTemplate);
        verify(processingJobRepo, never()).insertQueued(any(), any(), any(), anyInt(), any());
    }
}
