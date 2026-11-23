package vn.edu.examgrading.submission.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.examgrading.submission.anonymize.PageMasker;
import vn.edu.examgrading.submission.client.ResultClient;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AnonymizationWorker} (TICKET 03, spec §7/§8).
 * Covers happy path (T06/T09), overwrite/reprocess in place, idempotent skips,
 * schema/DLQ guards, stale fileVersion, template error → REVIEW_REQUIRED,
 * limit exceeded → FAILED/PDF_CORRUPT and technical-error retry/exhaustion.
 */
@ExtendWith(MockitoExtension.class)
class AnonymizationWorkerTest {

    private static final byte[] ORIGINAL_BYTES = "original-pdf".getBytes();
    private static final byte[] ANONYMIZED_BYTES = "anonymized-pdf".getBytes();
    private static final String ANONYMIZED_BUCKET = "anonymized";

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private ObjectStorageGateway storageGateway;
    @Mock private SubmissionRepositoryMarker submissionRepo;
    @Mock private ProcessingJobRepository processingJobRepo;
    @Mock private PaperTemplateRepository templateRepo;
    @Mock private ExamFileRepository examFileRepo;
    @Mock private SubmissionEventRepository eventRepo;
    @Mock private ResultClient resultClient;
    @Mock private PageMasker pageMasker;
    @Mock private JdbcTemplate jdbcTemplate;

    private AnonymizationWorker worker;
    private Clock clock;

    private final UUID examId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();

    private final StorageProperties storageProps = new StorageProperties(
        "s3", "http://localhost:9000", "us-east-1", "key", "secret",
        "original", ANONYMIZED_BUCKET, "http://localhost:8080/storage",
        52428800L, 1024L);

    private final PaperTemplateRepository.MaskRegions regions = new PaperTemplateRepository.MaskRegions(
        new PaperTemplateRepository.Region(0.1, 0.1, 0.5, 0.3),
        new PaperTemplateRepository.Region(0.15, 0.15, 0.3, 0.1),
        "Helvetica", 14.0, "CENTER");

    @BeforeEach
    void setUp() {
        clock = Clock.systemUTC();
        worker = new AnonymizationWorker(
            rabbitTemplate, storageGateway, submissionRepo, processingJobRepo,
            templateRepo, examFileRepo, eventRepo, resultClient, storageProps,
            pageMasker, jdbcTemplate, clock);
    }

    private void stubJob(int attempt, boolean overwrite, UUID jobTemplateId) {
        when(processingJobRepo.findJobById(jobId))
            .thenReturn(Optional.of(new ProcessingJobRepository.JobInfo(
                jobId, submissionId, "ANONYMIZE", "QUEUED", attempt,
                null, null, null, 1, false, overwrite, jobTemplateId)));
    }

    private void stubJobStatus(String status) {
        when(processingJobRepo.findJobById(jobId))
            .thenReturn(Optional.of(new ProcessingJobRepository.JobInfo(
                jobId, submissionId, "ANONYMIZE", status, 0,
                null, null, null, 1, false, false, templateId)));
    }

    private void stubSubmission(SubmissionStatus status, int fileVersion) {
        Submission submission = new Submission(
            submissionId, examId, templateId, status,
            fileVersion, 0, null, 1024L, null, null, "original/key.pdf", null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));
    }

    private void stubOriginalAndMasking() throws Exception {
        when(examFileRepo.findCurrentOriginal(submissionId))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 1, ORIGINAL_BYTES.length, "original/key.pdf")));
        when(templateRepo.findMaskRegions(any())).thenReturn(Optional.of(regions));
        when(storageGateway.get("original/key.pdf")).thenReturn(ORIGINAL_BYTES);
    }

    private void stubMaskingSuccess() throws Exception {
        when(pageMasker.anonymize(eq(ORIGINAL_BYTES), eq(regions), eq("PH-001")))
            .thenReturn(ANONYMIZED_BYTES);
    }

    private AnonymizeJobMessage message(int fileVersion) {
        return new AnonymizeJobMessage(jobId, submissionId, examId, templateId,
            fileVersion, "PH-001");
    }

    private String expectedAnonymizedKey() {
        return examId + "/" + submissionId + "/v1.pdf";
    }

    @Test
    void happyPath_validatingSubmission_completesT06AndT09() throws Exception {
        stubJob(0, false, templateId);
        stubSubmission(SubmissionStatus.VALIDATING, 1);
        stubOriginalAndMasking();
        stubMaskingSuccess();
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING,
            SubmissionStatus.ANONYMIZING, 75)).thenReturn(1);
        when(submissionRepo.transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.READY_FOR_GRADING, 100)).thenReturn(1);

        worker.processAnonymizeJob(message(1));

        // T06 on receipt, T09 after the ANONYMIZED object is stored.
        verify(submissionRepo).transition(submissionId, SubmissionStatus.VALIDATING,
            SubmissionStatus.ANONYMIZING, 75);
        verify(eventRepo).append(submissionId, SubmissionStatus.ANONYMIZING, 75);
        verify(pageMasker).anonymize(ORIGINAL_BYTES, regions, "PH-001");
        verify(storageGateway).put(ANONYMIZED_BUCKET, expectedAnonymizedKey(),
            ANONYMIZED_BYTES, "application/pdf");
        // First anonymization inserts the ExamFile row (no current ANONYMIZED yet).
        verify(examFileRepo).insertAnonymized(any(), eq(submissionId), eq(1),
            eq((long) ANONYMIZED_BYTES.length), eq(expectedAnonymizedKey()), anyString());
        verify(submissionRepo).transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.READY_FOR_GRADING, 100);
        verify(eventRepo).append(submissionId, SubmissionStatus.READY_FOR_GRADING, 100);
        verify(processingJobRepo).updateJobStatus(jobId, "SUCCEEDED", 1);
    }

    @Test
    void overwriteJob_usesJobTemplateIdAndUpdatesExistingRow() throws Exception {
        UUID reprocessTemplate = UUID.randomUUID();
        stubJob(0, true, reprocessTemplate);
        stubSubmission(SubmissionStatus.ANONYMIZING, 1);
        stubOriginalAndMasking();
        stubMaskingSuccess();
        when(examFileRepo.updateAnonymized(eq(submissionId), anyLong(), anyString(), anyString()))
            .thenReturn(1);
        when(submissionRepo.transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.READY_FOR_GRADING, 100)).thenReturn(1);

        worker.processAnonymizeJob(message(1));

        // §8: templateId is read from ProcessingJob, never from the message.
        verify(templateRepo).findMaskRegions(reprocessTemplate);
        verify(examFileRepo).updateAnonymized(eq(submissionId), anyLong(),
            eq(expectedAnonymizedKey()), anyString());
        verify(examFileRepo, never()).insertAnonymized(any(), any(), anyInt(), anyLong(), any(), any());
        verify(storageGateway).put(ANONYMIZED_BUCKET, expectedAnonymizedKey(),
            ANONYMIZED_BYTES, "application/pdf");
        verify(processingJobRepo).updateJobStatus(jobId, "SUCCEEDED", 1);
    }

    @Test
    void overwriteJob_onReadyForGrading_rebuildsInPlace() throws Exception {
        stubJob(0, true, templateId);
        stubSubmission(SubmissionStatus.READY_FOR_GRADING, 1);
        stubOriginalAndMasking();
        stubMaskingSuccess();

        worker.processAnonymizeJob(message(1));

        // Reprocess must not be mistaken for a transient error: the worker
        // rebuilds the ANONYMIZED object even though the submission never left
        // READY_FOR_GRADING (T18/T27 tolerate this ordering).
        verify(storageGateway).get("original/key.pdf");
        verify(storageGateway).put(ANONYMIZED_BUCKET, expectedAnonymizedKey(),
            ANONYMIZED_BYTES, "application/pdf");
        verify(submissionRepo, never()).transition(submissionId, SubmissionStatus.VALIDATING,
            SubmissionStatus.ANONYMIZING, 75);
        verify(processingJobRepo).updateJobStatus(jobId, "SUCCEEDED", 1);
        // Already READY_FOR_GRADING → optimistic T09 update matches 0 rows,
        // so no duplicate READY_FOR_GRADING event is appended.
        verify(eventRepo, never()).append(submissionId, SubmissionStatus.READY_FOR_GRADING, 100);
    }

    @Test
    void readyForGrading_withoutOverwrite_marksJobSucceeded() throws Exception {
        stubJob(0, false, templateId);
        stubSubmission(SubmissionStatus.READY_FOR_GRADING, 1);

        worker.processAnonymizeJob(message(1));

        verify(processingJobRepo).updateJobStatus(jobId, "SUCCEEDED", 1);
        verify(storageGateway, never()).get(anyString());
        verify(pageMasker, never()).anonymize(any(), any(), any());
        verify(submissionRepo, never()).transition(any(), any(), any(), anyInt());
    }

    @Test
    void staleFileVersion_isAckedWithoutTouchingJobOrSubmission() {
        stubJob(0, false, templateId);
        stubSubmission(SubmissionStatus.VALIDATING, 2); // current = 2, message = 1

        worker.processAnonymizeJob(message(1));

        verify(processingJobRepo, never()).updateJobStatus(any(), anyString(), anyInt());
        verify(processingJobRepo, never()).updateJobError(any(), any(), any(), any());
        verify(submissionRepo, never()).transition(any(), any(), any(), anyInt());
        verify(storageGateway, never()).get(anyString());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void alreadySucceededJob_isSkipped() {
        stubJobStatus("SUCCEEDED");

        worker.processAnonymizeJob(message(1));

        verify(submissionRepo, never()).findById(any());
        verify(storageGateway, never()).get(anyString());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void jobNotFound_isAcked() {
        when(processingJobRepo.findJobById(jobId)).thenReturn(Optional.empty());

        worker.processAnonymizeJob(message(1));

        verify(submissionRepo, never()).findById(any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void unknownSchemaVersion_goesToDlq() {
        AnonymizeJobMessage bad = new AnonymizeJobMessage(
            AnonymizeJobMessage.SCHEMA_VERSION + 1, jobId, submissionId, examId,
            templateId, 1, "PH-001", 0, UUID.randomUUID());

        worker.processAnonymizeJob(bad);

        verify(rabbitTemplate).convertAndSend(eq("submission.anonymize.dlq"), eq(bad));
        verify(processingJobRepo, never()).findJobById(any());
    }

    @Test
    void jobStepMismatch_goesToDlq() {
        UUID otherSubmission = UUID.randomUUID();
        when(processingJobRepo.findJobById(jobId))
            .thenReturn(Optional.of(new ProcessingJobRepository.JobInfo(
                jobId, otherSubmission, "VALIDATE", "QUEUED", 0,
                null, null, null, 1, false, false, templateId)));

        worker.processAnonymizeJob(message(1));

        verify(rabbitTemplate).convertAndSend(eq("submission.anonymize.dlq"), any(AnonymizeJobMessage.class));
    }

    @Test
    void maskException_marksReviewRequiredAndCreatesReview_noRetry() throws Exception {
        stubJob(0, false, templateId);
        stubSubmission(SubmissionStatus.VALIDATING, 1);
        stubOriginalAndMasking();
        when(pageMasker.anonymize(eq(ORIGINAL_BYTES), eq(regions), eq("PH-001")))
            .thenThrow(new PageMasker.MaskException("TEMPLATE_MASK_ERROR", "bad mask"));
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING,
            SubmissionStatus.ANONYMIZING, 75)).thenReturn(1);
        when(submissionRepo.transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.REVIEW_REQUIRED, 0)).thenReturn(1);

        worker.processAnonymizeJob(message(1));

        verify(processingJobRepo).updateJobError(eq(jobId), eq("TEMPLATE_MASK_ERROR"),
            eq("bad mask"), isNull());
        verify(submissionRepo).transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.REVIEW_REQUIRED, 0);
        verify(eventRepo).append(submissionId, SubmissionStatus.REVIEW_REQUIRED, 0);
        verify(resultClient).createReview(submissionId, examId,
            "TEMPLATE_MASK_ERROR", "bad mask", "Anonymization template error");
        verify(storageGateway, never()).put(anyString(), anyString(), any(), anyString());
        verify(rabbitTemplate, never()).convertAndSend(anyString(),
            any(AnonymizeJobMessage.class), any(MessagePostProcessor.class));
    }

    @Test
    void limitExceeded_marksFailedPdfCorrupt_noRetry() throws Exception {
        stubJob(0, false, templateId);
        stubSubmission(SubmissionStatus.VALIDATING, 1);
        stubOriginalAndMasking();
        when(pageMasker.anonymize(eq(ORIGINAL_BYTES), eq(regions), eq("PH-001")))
            .thenThrow(new PageMasker.LimitExceededException("render pixels exceed limit"));
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING,
            SubmissionStatus.ANONYMIZING, 75)).thenReturn(1);
        when(submissionRepo.transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.FAILED, 0)).thenReturn(1);

        worker.processAnonymizeJob(message(1));

        verify(processingJobRepo).updateJobError(eq(jobId), eq("PDF_CORRUPT"),
            contains("LIMIT_EXCEEDED"), isNull());
        verify(processingJobRepo).updateJobStatus(jobId, "FAILED", 0);
        verify(submissionRepo).transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.FAILED, 0);
        verify(jdbcTemplate).update(contains("failure_code"), eq("PDF_CORRUPT"), eq(submissionId));
        verify(rabbitTemplate, never()).convertAndSend(anyString(),
            any(AnonymizeJobMessage.class), any(MessagePostProcessor.class));
    }

    @Test
    void technicalError_firstAttempt_retriesVia30sQueue() {
        stubJob(0, false, templateId);
        stubSubmission(SubmissionStatus.VALIDATING, 1);
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING,
            SubmissionStatus.ANONYMIZING, 75)).thenReturn(1);
        when(examFileRepo.findCurrentOriginal(submissionId))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 1, ORIGINAL_BYTES.length, "original/key.pdf")));
        when(storageGateway.get("original/key.pdf")).thenThrow(new RuntimeException("minio down"));

        worker.processAnonymizeJob(message(1));

        verify(processingJobRepo).updateJobError(eq(jobId), eq("TECHNICAL_ERROR"),
            anyString(), any(Instant.class));
        verify(processingJobRepo).updateJobStatus(jobId, "QUEUED", 1);
        verify(rabbitTemplate).convertAndSend(eq("submission.anonymize.retry.30s"),
            any(AnonymizeJobMessage.class), any(MessagePostProcessor.class));
        verify(submissionRepo, never()).transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.FAILED, 0);
    }

    @Test
    void technicalError_retryExhausted_marksFailed() {
        stubJob(3, false, templateId);
        stubSubmission(SubmissionStatus.ANONYMIZING, 1);
        when(examFileRepo.findCurrentOriginal(submissionId))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 1, ORIGINAL_BYTES.length, "original/key.pdf")));
        when(storageGateway.get("original/key.pdf")).thenThrow(new RuntimeException("minio down"));
        when(submissionRepo.transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.FAILED, 0)).thenReturn(1);

        worker.processAnonymizeJob(message(1));

        verify(processingJobRepo).updateJobError(eq(jobId), eq("ANONYMIZE_RETRY_EXHAUSTED"),
            anyString(), isNull());
        verify(processingJobRepo).updateJobStatus(jobId, "FAILED", 3);
        verify(jdbcTemplate).update(contains("failure_code"), eq("ANONYMIZE_RETRY_EXHAUSTED"), eq(submissionId));
        verify(rabbitTemplate, never()).convertAndSend(anyString(),
            any(AnonymizeJobMessage.class), any(MessagePostProcessor.class));
    }

    @Test
    void unexpectedSubmissionStatus_isTreatedAsTechnicalError() {
        stubJob(0, false, templateId);
        stubSubmission(SubmissionStatus.UPLOADED, 1); // not VALIDATING/ANONYMIZING

        worker.processAnonymizeJob(message(1));

        // Illegal state → transient-error path → delayed retry, job stays alive.
        verify(processingJobRepo).updateJobError(eq(jobId), eq("TECHNICAL_ERROR"), anyString(), any(Instant.class));
        verify(rabbitTemplate).convertAndSend(eq("submission.anonymize.retry.30s"),
            any(AnonymizeJobMessage.class), any(MessagePostProcessor.class));
    }
}
