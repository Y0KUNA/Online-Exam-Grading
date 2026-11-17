package vn.edu.examgrading.submission.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.submission.domain.SubmissionError;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.InMemoryObjectStorage;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
            "http://localhost:8080/storage",
            52428800L,
            1024L
        );
        clock = Clock.systemUTC();
        service = new SubmissionUploadServiceImpl(
            submissionRepo, templateRepo, eventRepo, examFileRepo, processingJobRepo,
            gateway, props, clock, 10);
    }

    @Test
    void init_withValidRequest_returnsUploadTicket() {
        when(templateRepo.existsActive(templateId)).thenReturn(true);

        InitSubmissionRequest request = new InitSubmissionRequest(
            candidateId, templateId, "test.pdf", 52428800L, "application/pdf");

        InitSubmissionResponse response = service.init(examId, request, actorId);

        assertNotNull(response);
        assertEquals("UPLOADING", response.status());
        assertNotNull(response.upload());
        assertNotNull(response.upload().url());
        assertNotNull(response.upload().fields());
        assertTrue(response.upload().fields().containsKey("key"));
        assertNotNull(response.uploadExpiresAt());

        verify(submissionRepo).insertUploading(any(UUID.class), eq(examId), eq(candidateId),
            eq(templateId), any(Instant.class), eq(52428800L), any(String.class));
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

        verify(submissionRepo, never()).insertUploading(any(), any(), any(), any(), any(), anyLong(), any());
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
    void init_withDuplicateCandidate_throws409() {
        when(templateRepo.existsActive(templateId)).thenReturn(true);
        doThrow(new DuplicateKeyException("duplicate key")).when(submissionRepo)
            .insertUploading(any(UUID.class), eq(examId), eq(candidateId), eq(templateId),
                any(Instant.class), anyLong(), any(String.class));

        InitSubmissionRequest request = new InitSubmissionRequest(
            candidateId, templateId, "test.pdf", 52428800L, "application/pdf");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.init(examId, request, actorId));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(SubmissionError.CANDIDATE_ALREADY_BOUND, ex.getReason());
    }

    @Test
    void listForExam_returnsSubmissions() {
        when(submissionRepo.findByExam(examId)).thenReturn(java.util.List.of());

        List<Map<String, Object>> result = service.listForExam(examId);

        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void detail_notFound_throws404() {
        UUID submissionId = UUID.randomUUID();
        when(submissionRepo.findById(submissionId)).thenReturn(java.util.Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.detail(submissionId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals(SubmissionError.INVALID_STATE, ex.getReason());
    }
}
