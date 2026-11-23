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
import vn.edu.examgrading.submission.messaging.ValidationWorker;
import vn.edu.examgrading.submission.messaging.SubmissionOutbox;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private SubmissionOutbox outbox;

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
            gateway, props, clock, identityClient, rabbitTemplate, outbox, 10);
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
        when(submissionRepo.findByExam(examId)).thenReturn(List.of());

        List<Map<String, Object>> result = service.listForExam(examId);

        assertNotNull(result);
        assertEquals(0, result.size());
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
    void complete_withUploadedFile_publishesFullValidateMessage() {
        UUID submissionId = UUID.randomUUID();
        String objectKey = "exams/" + examId + "/" + submissionId + "/v1.pdf";
        Submission submission = new Submission(
            submissionId, examId, templateId, SubmissionStatus.UPLOADING,
            1, 0, Instant.now().plusSeconds(600),
            100L, null, objectKey, null, 0, Instant.now());
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
        verify(outbox).enqueueValidate(
            argThat((ValidationWorker.ValidateJobMessage m) -> m.schemaVersion() == ValidationWorker.ValidateJobMessage.SCHEMA_VERSION
                && m.submissionId().equals(submissionId) && m.examId().equals(examId)
                && m.templateId().equals(templateId) && m.fileVersion() == 1
                && !m.force() && m.attempt() == 0 && m.correlationId() != null));
        verify(eventRepo).append(submissionId, SubmissionStatus.UPLOADED, 0);
    }

    @Test
    void complete_whenAlreadyUploaded_isIdempotent() {
        UUID submissionId = UUID.randomUUID();
        Submission submission = new Submission(
            submissionId, examId, templateId, SubmissionStatus.UPLOADED,
            1, 0, null, 100L, null, "key", null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));

        CompleteSubmissionResponse response = service.complete(submissionId);

        assertEquals("UPLOADED", response.status());
        verifyNoInteractions(outbox);
        verify(processingJobRepo, never()).insertQueued(any(), any(), any(), anyInt(), any());
    }

    @Test
    void retryValidation_requeuesFailedSubmissionWithTemplateOverride() {
        UUID submissionId = UUID.randomUUID();
        String objectKey = "original/retry.pdf";
        Submission submission = new Submission(submissionId, examId, templateId, SubmissionStatus.REVIEW_REQUIRED,
            2, 0, null, 100L, 100L, objectKey, "TEMPLATE_SIZE_MISMATCH", 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));
        when(submissionRepo.transition(submissionId, SubmissionStatus.REVIEW_REQUIRED,
            SubmissionStatus.VALIDATING, 50)).thenReturn(1);
        gateway.put(objectKey, new byte[100], "application/pdf");

        Map<String, Object> result = service.retryValidation(submissionId, true);

        assertEquals("VALIDATING", result.get("status"));
        assertEquals(true, result.get("forceTemplateSize"));
        verify(submissionRepo).clearFailureCode(submissionId);
        verify(eventRepo).append(submissionId, SubmissionStatus.VALIDATING, 50);
        verify(processingJobRepo).insertQueued(any(UUID.class), eq(submissionId), eq("VALIDATE"), eq(2), eq(templateId));
        verify(outbox).enqueueValidate(
            argThat((ValidationWorker.ValidateJobMessage job) -> job.force() && job.submissionId().equals(submissionId)));
    }

    @Test
    void retryValidation_forceRecoversProcessingJobStuckInValidating() {
        UUID submissionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Submission submission = new Submission(submissionId, examId, templateId, SubmissionStatus.VALIDATING,
            1, 50, null, 100L, 100L, "original/stuck.pdf", null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));
        when(processingJobRepo.findJobBySubmissionAndStep(submissionId, "VALIDATE")).thenReturn(Optional.of(
            new ProcessingJobRepository.JobInfo(jobId, submissionId, "VALIDATE", "PROCESSING", 2,
                null, null, null, 1, false, false, templateId)));

        Map<String, Object> result = service.retryValidation(submissionId, false, true);

        assertEquals(true, result.get("recovered"));
        verify(outbox).enqueueValidate(argThat((ValidationWorker.ValidateJobMessage message) ->
            message.jobId().equals(jobId) && message.submissionId().equals(submissionId)));
        verify(processingJobRepo).updateJobStatus(jobId, "QUEUED", 2);
        verify(submissionRepo, never()).transition(any(), any(), any(), anyInt());
    }
}
