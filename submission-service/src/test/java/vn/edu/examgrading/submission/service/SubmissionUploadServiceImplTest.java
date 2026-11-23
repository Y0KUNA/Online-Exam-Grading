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
import vn.edu.examgrading.submission.dto.ReplaceFileRequest;
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
    private vn.edu.examgrading.submission.repository.FileReplacementRepository fileReplacementRepo;

    @Mock
    private IdentityClient identityClient;

    @Mock
    private vn.edu.examgrading.submission.client.ResultClient resultClient;

    @Mock
    private vn.edu.examgrading.submission.client.GradingClient gradingClient;

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
            fileReplacementRepo, gateway, props, clock, identityClient, resultClient,
            gradingClient, rabbitTemplate, jdbcTemplate, 10);
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

    // ---------- §4.4 replace-file (step 1) ----------

    private Submission replaceableSubmission(SubmissionStatus status, String failureCode) {
        return new Submission(UUID.randomUUID(), examId, templateId, status,
            1, 100, null, 100L, 95L, null, "key", failureCode, 0, Instant.now());
    }

    private void stubReplaceableHappyPath(Submission submission) {
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(gradingClient.attemptSummary(submission.submissionId()))
            .thenReturn(Map.of("confirmedCount", 0, "draftCount", 0, "openAssignmentCount", 0));
        when(fileReplacementRepo.findPending(eq(submission.submissionId()), any()))
            .thenReturn(Optional.empty());
    }

    @Test
    void replaceFile_failedScanError_createsPendingReplacementAndTicket() {
        Submission submission = replaceableSubmission(SubmissionStatus.FAILED, "PDF_CORRUPT");
        stubReplaceableHappyPath(submission);
        UUID actorId = UUID.randomUUID();

        Map<String, Object> res = service.replaceFile(submission.submissionId(),
            new ReplaceFileRequest("scan bị nhòe", "rescan.pdf", 2048L, "application/pdf"),
            bearerJwtWithSub(actorId));

        assertEquals(submission.submissionId().toString(), res.get("submissionId").toString());
        // status unchanged until complete
        assertEquals("FAILED", res.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> replacement = (Map<String, Object>) res.get("replacement");
        assertEquals(2, replacement.get("version"));
        assertNotNull(replacement.get("replacementId"));
        assertNotNull(replacement.get("uploadExpiresAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> upload = (Map<String, Object>) replacement.get("upload");
        assertNotNull(upload.get("url"));
        assertNotNull(upload.get("fields"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReplacementRepo).insertPending(
            eq((UUID) replacement.get("replacementId")), eq(submission.submissionId()), eq(2),
            keyCaptor.capture(), eq(2048L), eq("scan bị nhòe"), any(), eq(actorId));
        assertTrue(keyCaptor.getValue().endsWith("/v2.pdf"));
        // audit without object key/URL
        verify(jdbcTemplate).update(contains("REPLACE_FILE_REQUESTED"), any(), eq(actorId),
            eq(submission.submissionId()), contains("\"version\":2"));
        // no state mutation yet
        verify(submissionRepo, never()).transition(any(), any(), any(), anyInt());
    }

    @Test
    void replaceFile_reviewRequired_needsAuthorizedReview() {
        Submission submission = replaceableSubmission(SubmissionStatus.REVIEW_REQUIRED, null);
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(resultClient.getReplaceAuthorization(submission.submissionId()))
            .thenReturn(Map.of("authorized", false, "status", "OPEN"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.replaceFile(submission.submissionId(),
                new ReplaceFileRequest("reason", "f.pdf", 100L, "application/pdf"), null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("REVIEW_NOT_AUTHORIZED", ex.getReason());
        verify(fileReplacementRepo, never()).insertPending(any(), any(), anyInt(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void replaceFile_reviewRequired_authorized_succeeds() {
        Submission submission = replaceableSubmission(SubmissionStatus.REVIEW_REQUIRED, null);
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(resultClient.getReplaceAuthorization(submission.submissionId()))
            .thenReturn(Map.of("authorized", true, "status", "ACTION_AUTHORIZED",
                "authorizedAction", "REPLACE_FILE", "reasonType", "ANONYMIZATION"));
        when(gradingClient.attemptSummary(submission.submissionId()))
            .thenReturn(Map.of("confirmedCount", 0));
        when(fileReplacementRepo.findPending(eq(submission.submissionId()), any()))
            .thenReturn(Optional.empty());

        Map<String, Object> res = service.replaceFile(submission.submissionId(),
            new ReplaceFileRequest("lý do", "f.pdf", 100L, "application/pdf"), null);
        assertEquals("REVIEW_REQUIRED", res.get("status"));
        verify(fileReplacementRepo).insertPending(any(), eq(submission.submissionId()), eq(2),
            any(), eq(100L), any(), any(), any());
    }

    @Test
    void replaceFile_confirmedAttempt_blocked() {
        Submission submission = replaceableSubmission(SubmissionStatus.FAILED, "PDF_CORRUPT");
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(gradingClient.attemptSummary(submission.submissionId()))
            .thenReturn(Map.of("confirmedCount", 1, "draftCount", 0, "openAssignmentCount", 0));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.replaceFile(submission.submissionId(),
                new ReplaceFileRequest("reason", "f.pdf", 100L, "application/pdf"), null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("ATTEMPTS_EXIST", ex.getReason());
    }

    @Test
    void replaceFile_disallowedStates_andPendingConflict() {
        // non-eligible status
        Submission uploaded = replaceableSubmission(SubmissionStatus.UPLOADED, null);
        when(submissionRepo.findById(uploaded.submissionId()))
            .thenReturn(Optional.of(uploaded));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.replaceFile(uploaded.submissionId(),
                new ReplaceFileRequest("reason", "f.pdf", 100L, "application/pdf"), null));
        assertEquals("REPLACE_NOT_ALLOWED", ex.getReason());

        // pending replacement already exists
        Submission failed = replaceableSubmission(SubmissionStatus.FAILED, "PAGE_COUNT_INVALID");
        when(submissionRepo.findById(failed.submissionId()))
            .thenReturn(Optional.of(failed));
        when(gradingClient.attemptSummary(failed.submissionId()))
            .thenReturn(Map.of("confirmedCount", 0));
        var pending = new vn.edu.examgrading.submission.repository.FileReplacementRepository.Replacement(
            UUID.randomUUID(), failed.submissionId(), 2, "k", 100L, "r", "PENDING",
            Instant.now().plusSeconds(60), UUID.randomUUID(), Instant.now(), null);
        when(fileReplacementRepo.findPending(eq(failed.submissionId()), any()))
            .thenReturn(Optional.of(pending));
        ex = assertThrows(ResponseStatusException.class,
            () -> service.replaceFile(failed.submissionId(),
                new ReplaceFileRequest("reason", "f.pdf", 100L, "application/pdf"), null));
        assertEquals("REPLACEMENT_IN_PROGRESS", ex.getReason());
    }

    @Test
    void replaceFile_payloadValidation() {
        Submission submission = replaceableSubmission(SubmissionStatus.FAILED, "PDF_CORRUPT");
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));

        assertEquals("REASON_REQUIRED", assertThrows(ResponseStatusException.class,
            () -> service.replaceFile(submission.submissionId(),
                new ReplaceFileRequest(" ", "f.pdf", 100L, "application/pdf"), null)).getReason());
        assertEquals(SubmissionError.UNSUPPORTED_CONTENT_TYPE, assertThrows(ResponseStatusException.class,
            () -> service.replaceFile(submission.submissionId(),
                new ReplaceFileRequest("r", "f.png", 100L, "image/png"), null)).getReason());
        assertEquals(SubmissionError.INVALID_FILE_SIZE, assertThrows(ResponseStatusException.class,
            () -> service.replaceFile(submission.submissionId(),
                new ReplaceFileRequest("r", "f.pdf", 52428801L, "application/pdf"), null)).getReason());
        verify(fileReplacementRepo, never()).insertPending(any(), any(), anyInt(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void replaceFile_pendingSlotRace_mapsToConflict() {
        Submission submission = replaceableSubmission(SubmissionStatus.FAILED, "PDF_CORRUPT");
        stubReplaceableHappyPath(submission);
        doThrow(new DuplicateKeyException("uq")).when(fileReplacementRepo)
            .insertPending(any(), any(), anyInt(), any(), anyLong(), any(), any(), any());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.replaceFile(submission.submissionId(),
                new ReplaceFileRequest("reason", "f.pdf", 100L, "application/pdf"), null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("REPLACEMENT_IN_PROGRESS", ex.getReason());
    }

    // ---------- §4.4 replace-file complete ----------

    private vn.edu.examgrading.submission.repository.FileReplacementRepository.Replacement
            pendingReplacement(UUID submissionId, int version, String objectKey) {
        return new vn.edu.examgrading.submission.repository.FileReplacementRepository.Replacement(
            UUID.randomUUID(), submissionId, version, objectKey, 100L, "reason", "PENDING",
            Instant.now().plusSeconds(600), UUID.randomUUID(), Instant.now(), null);
    }

    private void stubCompleteBase(Submission submission,
            vn.edu.examgrading.submission.repository.FileReplacementRepository.Replacement repl) {
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(fileReplacementRepo.findById(repl.replacementId())).thenReturn(Optional.of(repl));
        when(gradingClient.attemptSummary(submission.submissionId()))
            .thenReturn(Map.of("confirmedCount", 0, "draftCount", 0, "openAssignmentCount", 0));
    }

    @Test
    void completeReplacement_failedScanError_commitsAndPublishes() throws Exception {
        Submission submission = replaceableSubmission(SubmissionStatus.FAILED, "PDF_CORRUPT");
        var repl = pendingReplacement(submission.submissionId(), 2, "k/v2.pdf");
        stubCompleteBase(submission, repl);
        gateway.put("original", "k/v2.pdf", new byte[]{1, 2, 3}, "application/pdf");
        when(submissionRepo.completeReplacement(submission.submissionId(), 2)).thenReturn(1);

        Map<String, Object> res = service.completeReplacement(submission.submissionId(), repl.replacementId());

        assertEquals("UPLOADED", res.get("status"));
        assertEquals(2, res.get("version"));
        // saga order: discard-open-work before local mutation
        var inOrder = inOrder(gradingClient, examFileRepo, submissionRepo);
        inOrder.verify(gradingClient).discardOpenWork(submission.submissionId(), repl.replacementId());
        inOrder.verify(examFileRepo).clearCurrent(submission.submissionId());
        inOrder.verify(examFileRepo).insertOriginalVersion(any(), eq(submission.submissionId()),
            eq(2), eq(3L), eq("k/v2.pdf"));
        inOrder.verify(submissionRepo).completeReplacement(submission.submissionId(), 2);
        // no review on the FAILED path
        verify(resultClient, never()).resolveReplaceReview(any());
        verify(fileReplacementRepo).markCompleted(eq(repl.replacementId()), any());
        verify(processingJobRepo).insertQueued(any(), eq(submission.submissionId()), eq("VALIDATE"), eq(2), any());
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("validate"),
            argThat((ValidationWorker.ValidateJobMessage m) -> m.fileVersion() == 2));
        verify(eventRepo).append(submission.submissionId(), SubmissionStatus.UPLOADED, 0);
        verify(jdbcTemplate).update(contains("REPLACE_FILE_COMPLETED"), any(), any(), any(), any());
    }

    @Test
    void completeReplacement_reviewRequired_resolvesReview() throws Exception {
        Submission submission = replaceableSubmission(SubmissionStatus.REVIEW_REQUIRED, null);
        var repl = pendingReplacement(submission.submissionId(), 3, "k/v3.pdf");
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(fileReplacementRepo.findById(repl.replacementId())).thenReturn(Optional.of(repl));
        when(resultClient.getReplaceAuthorization(submission.submissionId()))
            .thenReturn(Map.of("authorized", true, "status", "ACTION_AUTHORIZED"));
        when(gradingClient.attemptSummary(submission.submissionId()))
            .thenReturn(Map.of("confirmedCount", 0));
        gateway.put("original", "k/v3.pdf", new byte[]{9}, "application/pdf");
        when(submissionRepo.completeReplacement(submission.submissionId(), 3)).thenReturn(1);

        Map<String, Object> res = service.completeReplacement(submission.submissionId(), repl.replacementId());

        assertEquals("UPLOADED", res.get("status"));
        verify(resultClient).resolveReplaceReview(submission.submissionId());
    }

    @Test
    void completeReplacement_alreadyCompleted_isIdempotent() {
        Submission submission = replaceableSubmission(SubmissionStatus.UPLOADED, null);
        var repl = new vn.edu.examgrading.submission.repository.FileReplacementRepository.Replacement(
            UUID.randomUUID(), submission.submissionId(), 2, "k", 100L, "r", "COMPLETED",
            Instant.now().minusSeconds(10), UUID.randomUUID(), Instant.now(), Instant.now());
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(fileReplacementRepo.findById(repl.replacementId())).thenReturn(Optional.of(repl));

        Map<String, Object> res = service.completeReplacement(submission.submissionId(), repl.replacementId());

        assertEquals("UPLOADED", res.get("status"));
        assertEquals(2, res.get("version"));
        verifyNoInteractions(gradingClient, examFileRepo, rabbitTemplate);
    }

    @Test
    void completeReplacement_expired_uploadMissing_tooLarge() throws Exception {
        Submission submission = replaceableSubmission(SubmissionStatus.FAILED, "PDF_CORRUPT");
        // expired
        var expired = new vn.edu.examgrading.submission.repository.FileReplacementRepository.Replacement(
            UUID.randomUUID(), submission.submissionId(), 2, "k", 100L, "r", "PENDING",
            Instant.now().minusSeconds(1), UUID.randomUUID(), Instant.now(), null);
        when(submissionRepo.findById(submission.submissionId())).thenReturn(Optional.of(submission));
        when(fileReplacementRepo.findById(expired.replacementId())).thenReturn(Optional.of(expired));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.completeReplacement(submission.submissionId(), expired.replacementId()));
        assertEquals("REPLACEMENT_EXPIRED", ex.getReason());
        verify(fileReplacementRepo).markExpired(expired.replacementId());

        // object missing
        var missing = pendingReplacement(submission.submissionId(), 2, "k/missing.pdf");
        when(fileReplacementRepo.findById(missing.replacementId())).thenReturn(Optional.of(missing));
        ex = assertThrows(ResponseStatusException.class,
            () -> service.completeReplacement(submission.submissionId(), missing.replacementId()));
        assertEquals(SubmissionError.UPLOAD_NOT_FOUND, ex.getReason());

        // oversized → object deleted + CANCELLED + 422, submission untouched
        var big = pendingReplacement(submission.submissionId(), 2, "k/big.pdf");
        when(fileReplacementRepo.findById(big.replacementId())).thenReturn(Optional.of(big));
        gateway.put("original", "k/big.pdf", new byte[2000], "application/pdf");
        ex = assertThrows(ResponseStatusException.class,
            () -> service.completeReplacement(submission.submissionId(), big.replacementId()));
        assertEquals(SubmissionError.FILE_TOO_LARGE, ex.getReason());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        verify(fileReplacementRepo).markCancelled(big.replacementId());
        verify(submissionRepo, never()).completeReplacement(any(), anyInt());
    }

    @Test
    void completeReplacement_discard503_leavesEverythingUntouched() {
        Submission submission = replaceableSubmission(SubmissionStatus.FAILED, "PDF_CORRUPT");
        var repl = pendingReplacement(submission.submissionId(), 2, "k/v2.pdf");
        stubCompleteBase(submission, repl);
        gateway.put("original", "k/v2.pdf", new byte[]{1}, "application/pdf");
        doThrow(new org.springframework.web.client.HttpServerErrorException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
            .when(gradingClient).discardOpenWork(submission.submissionId(), repl.replacementId());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.completeReplacement(submission.submissionId(), repl.replacementId()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        verify(examFileRepo, never()).clearCurrent(any());
        verify(submissionRepo, never()).completeReplacement(any(), anyInt());
        verify(fileReplacementRepo, never()).markCompleted(any(), any());
    }

    // ---------- §4.4 replace-file cancel ----------

    @Test
    void cancelReplacement_pending_marksCancelledAndDeletesObject() throws Exception {
        Submission submission = replaceableSubmission(SubmissionStatus.FAILED, "PDF_CORRUPT");
        var repl = pendingReplacement(submission.submissionId(), 2, "k/v2.pdf");
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(fileReplacementRepo.findPending(eq(submission.submissionId()), any()))
            .thenReturn(Optional.of(repl));
        gateway.put("original", "k/v2.pdf", new byte[]{1}, "application/pdf");

        service.cancelReplacement(submission.submissionId());

        verify(fileReplacementRepo).markCancelled(repl.replacementId());
        assertTrue(gateway.head("original", "k/v2.pdf").isEmpty());
        // submission state untouched
        verify(submissionRepo, never()).completeReplacement(any(), anyInt());
    }

    @Test
    void cancelReplacement_none_throws404() {
        Submission submission = replaceableSubmission(SubmissionStatus.FAILED, "PDF_CORRUPT");
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(fileReplacementRepo.findPending(eq(submission.submissionId()), any()))
            .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.cancelReplacement(submission.submissionId()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("REPLACEMENT_NOT_FOUND", ex.getReason());
    }

    // ---------- §4.4 file access (?version=) ----------

    @Test
    void fileAccess_original_supersededVersion_servedFromOriginalBucket() {
        Submission submission = readySubmission(UUID.randomUUID(), SubmissionStatus.READY_FOR_GRADING);
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(examFileRepo.findByTypeAndVersion(submission.submissionId(), "ORIGINAL", 1))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 1, 100L, "old/v1.pdf")));

        Map<String, Object> res = service.fileAccess(submission.submissionId(), "ORIGINAL", 1);

        assertNotNull(res.get("url"));
        assertEquals(1, res.get("version"));
    }

    @Test
    void fileAccess_original_default_usesCurrent() {
        Submission submission = readySubmission(UUID.randomUUID(), SubmissionStatus.READY_FOR_GRADING);
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(examFileRepo.findCurrentByType(submission.submissionId(), "ORIGINAL"))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 2, 100L, "new/v2.pdf")));

        Map<String, Object> res = service.fileAccess(submission.submissionId(), "ORIGINAL", null);

        assertEquals(2, res.get("version"));
    }

    @Test
    void fileAccess_anonymized_alwaysCurrentAndNotReadyDuringReprocess() {
        Submission submission = readySubmission(UUID.randomUUID(), SubmissionStatus.READY_FOR_GRADING);
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(examFileRepo.findCurrentByType(submission.submissionId(), "ANONYMIZED"))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 2, 90L, "anon/v2.pdf")));

        Map<String, Object> res = service.fileAccess(submission.submissionId(), "ANONYMIZED", 1);

        // version param ignored — always the current anonymized file
        assertEquals(2, res.get("version"));

        Submission reprocessing = readySubmission(submission.submissionId(), SubmissionStatus.ANONYMIZING);
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(reprocessing));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.fileAccess(submission.submissionId(), "ANONYMIZED", null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("FILE_NOT_READY", ex.getReason());
    }

    // ---------- §4.4 re-replace after failed reprocess ----------

    @Test
    void replaceFile_secondTimeAfterReprocessFailure_incrementsVersion() {
        // Pipeline re-failed with a scan error again → EO may replace once more;
        // version continues from current_file_version (2 after first replace).
        Submission submission = new Submission(UUID.randomUUID(), examId, templateId,
            SubmissionStatus.FAILED, 2, 0, null, 100L, 95L, null, "key",
            "PDF_CORRUPT", 0, Instant.now());
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(gradingClient.attemptSummary(submission.submissionId()))
            .thenReturn(Map.of("confirmedCount", 0));
        when(fileReplacementRepo.findPending(eq(submission.submissionId()), any()))
            .thenReturn(Optional.empty());

        Map<String, Object> res = service.replaceFile(submission.submissionId(),
            new ReplaceFileRequest("scan vẫn lỗi", "rescan2.pdf", 2048L, "application/pdf"), null);

        @SuppressWarnings("unchecked")
        Map<String, Object> replacement = (Map<String, Object>) res.get("replacement");
        assertEquals(3, replacement.get("version"));
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileReplacementRepo).insertPending(any(), eq(submission.submissionId()), eq(3),
            keyCaptor.capture(), eq(2048L), any(), any(), any());
        assertTrue(keyCaptor.getValue().endsWith("/v3.pdf"));
        // overdue pending rows are expired lazily before the slot check
        verify(fileReplacementRepo).expireOverdue(eq(submission.submissionId()), any());
    }

    // ---------- §4.6/T27 anonymization-issues ----------

    @Test
    void anonymizationIssue_readyForGrading_createsReviewThenTransitions() {
        Submission submission = readySubmission(UUID.randomUUID(), SubmissionStatus.READY_FOR_GRADING);
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(examFileRepo.findCurrentByType(submission.submissionId(), "ANONYMIZED"))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 1, 90L, "anon/v1.pdf")));
        UUID reviewId = UUID.randomUUID();
        when(resultClient.createAnonymizationIssue(eq(submission.submissionId()), eq(examId),
                eq(1), eq(templateId), eq("vùng che thiếu"), isNull(), isNull()))
            .thenReturn(Map.of("reviewId", reviewId, "status", "OPEN"));
        // createdBy is null for a missing/invalid JWT sub (uuid column).
        when(submissionRepo.transition(submission.submissionId(),
                SubmissionStatus.READY_FOR_GRADING, SubmissionStatus.REVIEW_REQUIRED, 100))
            .thenReturn(1);

        Map<String, Object> res = service.createAnonymizationIssue(
            submission.submissionId(), "vùng che thiếu", null, null);

        assertEquals(reviewId, res.get("reviewId"));
        assertEquals("OPEN", res.get("status"));
        // order: review created BEFORE the status transition
        var inOrder = inOrder(resultClient, submissionRepo);
        inOrder.verify(resultClient).createAnonymizationIssue(any(), any(), anyInt(), any(),
            any(), any(), any());
        inOrder.verify(submissionRepo).transition(submission.submissionId(),
            SubmissionStatus.READY_FOR_GRADING, SubmissionStatus.REVIEW_REQUIRED, 100);
        verify(eventRepo).append(submission.submissionId(), SubmissionStatus.REVIEW_REQUIRED, 100);
    }

    @Test
    void anonymizationIssue_wrongState_andBlankReason() {
        Submission uploaded = readySubmission(UUID.randomUUID(), SubmissionStatus.UPLOADED);
        when(submissionRepo.findById(uploaded.submissionId())).thenReturn(Optional.of(uploaded));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.createAnonymizationIssue(uploaded.submissionId(), "reason", null, null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(SubmissionError.INVALID_STATE, ex.getReason());

        // blank reason is rejected before any repository lookup
        UUID readyId = UUID.randomUUID();
        ex = assertThrows(ResponseStatusException.class,
            () -> service.createAnonymizationIssue(readyId, "  ", null, null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("REASON_REQUIRED", ex.getReason());
    }

    @Test
    void anonymizationIssue_noAnonymizedFile_409() {
        Submission ready = readySubmission(UUID.randomUUID(), SubmissionStatus.READY_FOR_GRADING);
        when(submissionRepo.findById(ready.submissionId())).thenReturn(Optional.of(ready));
        when(examFileRepo.findCurrentByType(ready.submissionId(), "ANONYMIZED"))
            .thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.createAnonymizationIssue(ready.submissionId(), "reason", null, null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(resultClient, never()).createAnonymizationIssue(any(), any(), anyInt(), any(),
            any(), any(), any());
    }

    @Test
    void anonymizationIssue_reviewAlreadyOpen_409() {
        Submission ready = readySubmission(UUID.randomUUID(), SubmissionStatus.READY_FOR_GRADING);
        when(submissionRepo.findById(ready.submissionId())).thenReturn(Optional.of(ready));
        when(examFileRepo.findCurrentByType(ready.submissionId(), "ANONYMIZED"))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 1, 90L, "anon/v1.pdf")));
        when(resultClient.createAnonymizationIssue(any(), any(), anyInt(), any(),
                any(), any(), any()))
            .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                HttpStatus.CONFLICT));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.createAnonymizationIssue(ready.submissionId(), "reason", null, null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("REVIEW_ALREADY_OPEN", ex.getReason());
        verify(submissionRepo, never()).transition(any(), any(), any(), anyInt());
    }

    @Test
    void anonymizationIssue_replayOnReviewRequired_returnsUnclosedReview() {
        Submission reviewRequired = readySubmission(UUID.randomUUID(), SubmissionStatus.REVIEW_REQUIRED);
        when(submissionRepo.findById(reviewRequired.submissionId()))
            .thenReturn(Optional.of(reviewRequired));
        UUID reviewId = UUID.randomUUID();
        when(resultClient.getReplaceAuthorization(reviewRequired.submissionId()))
            .thenReturn(Map.of("authorized", false, "reviewId", reviewId,
                "reasonType", "ANONYMIZATION", "status", "OPEN"));

        Map<String, Object> res = service.createAnonymizationIssue(
            reviewRequired.submissionId(), "reason", null, null);

        assertEquals(reviewId, res.get("reviewId"));
        assertEquals("OPEN", res.get("status"));
        verify(resultClient, never()).createAnonymizationIssue(any(), any(), anyInt(), any(),
            any(), any(), any());
    }

    // ---------- §4.1 retry T17/T18/T19 ----------

    @Test
    void retry_failedValidateExhausted_requeuesValidate() {
        Submission failed = new Submission(UUID.randomUUID(), examId, templateId,
            SubmissionStatus.FAILED, 1, 0, null, 100L, 95L, null, "key",
            "VALIDATE_RETRY_EXHAUSTED", 0, Instant.now());
        when(submissionRepo.findById(failed.submissionId())).thenReturn(Optional.of(failed));
        when(submissionRepo.transition(failed.submissionId(), SubmissionStatus.FAILED,
                SubmissionStatus.VALIDATING, 0)).thenReturn(1);

        Map<String, Object> res = service.retry(failed.submissionId(), "VALIDATE", null, null, null, null);

        assertEquals("VALIDATING", res.get("status"));
        verify(processingJobRepo).insertQueued(any(), eq(failed.submissionId()), eq("VALIDATE"),
            eq(1), eq(templateId), eq(false), eq(false));
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("validate"),
            any(ValidationWorker.ValidateJobMessage.class));
    }

    @Test
    void retry_failedWrongCodeOrStepMismatch() {
        Submission failed = new Submission(UUID.randomUUID(), examId, templateId,
            SubmissionStatus.FAILED, 1, 0, null, 100L, 95L, null, "key",
            "PDF_CORRUPT", 0, Instant.now());
        when(submissionRepo.findById(failed.submissionId())).thenReturn(Optional.of(failed));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.retry(failed.submissionId(), "VALIDATE", null, null, null, null))
            .getStatusCode());

        Submission anonFailed = new Submission(UUID.randomUUID(), examId, templateId,
            SubmissionStatus.FAILED, 1, 0, null, 100L, 95L, null, "key",
            "ANONYMIZE_RETRY_EXHAUSTED", 0, Instant.now());
        when(submissionRepo.findById(anonFailed.submissionId())).thenReturn(Optional.of(anonFailed));
        assertEquals("STEP_MISMATCH", assertThrows(ResponseStatusException.class,
            () -> service.retry(anonFailed.submissionId(), "VALIDATE", null, null, null, null))
            .getReason());
    }

    @Test
    void retry_reviewRequired_forceAnonymize_t17() {
        Submission rr = readySubmission(UUID.randomUUID(), SubmissionStatus.REVIEW_REQUIRED);
        UUID reviewId = UUID.randomUUID();
        when(submissionRepo.findById(rr.submissionId())).thenReturn(Optional.of(rr));
        when(resultClient.getReviewState(reviewId)).thenReturn(Map.of(
            "reviewId", reviewId, "submissionId", rr.submissionId().toString(),
            "status", "ACTION_AUTHORIZED", "authorizedAction", "FORCE_ANONYMIZE",
            "technicalCode", "TEMPLATE_SIZE_MISMATCH", "reasonType", "ANONYMIZATION"));
        when(gradingClient.attemptSummary(rr.submissionId()))
            .thenReturn(Map.of("confirmedCount", 0));
        when(submissionRepo.transition(rr.submissionId(), SubmissionStatus.REVIEW_REQUIRED,
                SubmissionStatus.VALIDATING, 0)).thenReturn(1);

        Map<String, Object> res = service.retry(rr.submissionId(), "VALIDATE", true, reviewId, null, null);

        assertEquals("VALIDATING", res.get("status"));
        verify(resultClient).resolveReviewAction(reviewId, "FORCE_ANONYMIZE");
        verify(processingJobRepo).insertQueued(any(), eq(rr.submissionId()), eq("VALIDATE"),
            eq(1), eq(templateId), eq(true), eq(false));
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("validate"),
            argThat((ValidationWorker.ValidateJobMessage m) -> m.force()));
    }

    @Test
    void retry_reviewRequired_reprocess_withTemplate_t18() {
        Submission rr = readySubmission(UUID.randomUUID(), SubmissionStatus.REVIEW_REQUIRED);
        UUID reviewId = UUID.randomUUID();
        UUID newTemplate = UUID.randomUUID();
        when(submissionRepo.findById(rr.submissionId())).thenReturn(Optional.of(rr));
        when(resultClient.getReviewState(reviewId)).thenReturn(Map.of(
            "reviewId", reviewId, "submissionId", rr.submissionId().toString(),
            "status", "ACTION_AUTHORIZED", "authorizedAction", "REPROCESS",
            "technicalCode", "MASK_INSUFFICIENT", "reasonType", "ANONYMIZATION"));
        when(templateRepo.findTemplateInfo(newTemplate))
            .thenReturn(Optional.of(new PaperTemplateRepository.TemplateInfo(newTemplate, 595, 842, true)));
        when(gradingClient.attemptSummary(rr.submissionId()))
            .thenReturn(Map.of("confirmedCount", 0));
        when(identityClient.createPseudonym(any(), any(), anyString()))
            .thenReturn(null);
        when(submissionRepo.transition(rr.submissionId(), SubmissionStatus.REVIEW_REQUIRED,
                SubmissionStatus.ANONYMIZING, 0)).thenReturn(1);

        Map<String, Object> res = service.retry(rr.submissionId(), "ANONYMIZE", null, reviewId, newTemplate, null);

        assertEquals("ANONYMIZING", res.get("status"));
        verify(resultClient).resolveReviewAction(reviewId, "REPROCESS");
        verify(submissionRepo).updateTemplateId(rr.submissionId(), newTemplate);
        // MASK_INSUFFICIENT → overwrite=true so the anonymized object is rebuilt in place
        verify(processingJobRepo).insertQueued(any(), eq(rr.submissionId()), eq("ANONYMIZE"),
            eq(1), eq(newTemplate), eq(false), eq(true));
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("anonymize"),
            any(vn.edu.examgrading.submission.messaging.AnonymizeJobMessage.class));
    }

    @Test
    void retry_reviewRequired_unauthorizedAndGuards() {
        Submission rr = readySubmission(UUID.randomUUID(), SubmissionStatus.REVIEW_REQUIRED);
        UUID reviewId = UUID.randomUUID();
        when(submissionRepo.findById(rr.submissionId())).thenReturn(Optional.of(rr));
        // missing reviewId
        assertEquals("REVIEW_ID_REQUIRED", assertThrows(ResponseStatusException.class,
            () -> service.retry(rr.submissionId(), "VALIDATE", true, null, null, null)).getReason());
        // review not authorized / wrong action
        when(resultClient.getReviewState(reviewId)).thenReturn(Map.of(
            "reviewId", reviewId, "submissionId", rr.submissionId().toString(),
            "status", "OPEN", "authorizedAction", "", "technicalCode", ""));
        assertEquals("REVIEW_NOT_AUTHORIZED", assertThrows(ResponseStatusException.class,
            () -> service.retry(rr.submissionId(), "VALIDATE", true, reviewId, null, null)).getReason());
        // FORCE_ANONYMIZE requires force=true
        when(resultClient.getReviewState(reviewId)).thenReturn(Map.of(
            "reviewId", reviewId, "submissionId", rr.submissionId().toString(),
            "status", "ACTION_AUTHORIZED", "authorizedAction", "FORCE_ANONYMIZE",
            "technicalCode", "TEMPLATE_SIZE_MISMATCH"));
        assertEquals("REVIEW_NOT_AUTHORIZED", assertThrows(ResponseStatusException.class,
            () -> service.retry(rr.submissionId(), "VALIDATE", false, reviewId, null, null)).getReason());
        // templateId with FORCE_ANONYMIZE → 422
        assertEquals("INVALID_TEMPLATE_ID", assertThrows(ResponseStatusException.class,
            () -> service.retry(rr.submissionId(), "VALIDATE", true, reviewId, UUID.randomUUID(), null)).getReason());
    }

    @Test
    void fileAccess_unknownVersionAndType() {
        Submission submission = readySubmission(UUID.randomUUID(), SubmissionStatus.READY_FOR_GRADING);
        when(submissionRepo.findById(submission.submissionId()))
            .thenReturn(Optional.of(submission));
        when(examFileRepo.findByTypeAndVersion(submission.submissionId(), "ORIGINAL", 9))
            .thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.fileAccess(submission.submissionId(), "ORIGINAL", 9)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.fileAccess(submission.submissionId(), "OTHER", null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.fileAccess(submission.submissionId(), "ORIGINAL", 0)).getStatusCode());
    }
}
