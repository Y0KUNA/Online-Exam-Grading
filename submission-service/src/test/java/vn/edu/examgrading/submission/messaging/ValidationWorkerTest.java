package vn.edu.examgrading.submission.messaging;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.client.ResultClient;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.dto.PseudonymResponse;
import vn.edu.examgrading.submission.messaging.ValidationWorker.ValidateJobMessage;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ValidationWorker} (TICKET 02, spec §8).
 * Covers happy path, PDF_CORRUPT, PAGE_COUNT_INVALID, TEMPLATE_SIZE_MISMATCH,
 * technical-error retry/exhaustion, schema guard → DLQ and stale fileVersion → ack.
 */
@ExtendWith(MockitoExtension.class)
class ValidationWorkerTest {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private ObjectStorageGateway storageGateway;
    @Mock private SubmissionRepositoryMarker submissionRepo;
    @Mock private ProcessingJobRepository processingJobRepo;
    @Mock private PaperTemplateRepository templateRepo;
    @Mock private SubmissionEventRepository eventRepo;
    @Mock private IdentityClient identityClient;
    @Mock private ResultClient resultClient;
    @Mock private JdbcTemplate jdbcTemplate;

    private ValidationWorker worker;
    private Clock clock;

    private final UUID examId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = Clock.systemUTC();
        worker = new ValidationWorker(
            rabbitTemplate, storageGateway, submissionRepo, processingJobRepo,
            templateRepo, eventRepo, identityClient, resultClient, jdbcTemplate, clock);
    }

    private void stubJob(int attempt) {
        when(processingJobRepo.findJobById(jobId))
            .thenReturn(Optional.of(new ProcessingJobRepository.JobInfo(
                jobId, submissionId, "VALIDATE", "QUEUED", attempt, null, null, null, 1, false, false, templateId)));
    }

    private void stubSubmission(SubmissionStatus status, int fileVersion, String objectKey) {
        Submission submission = new Submission(
            submissionId, examId, templateId, status,
            fileVersion, 0, null, 1024L, null, null, objectKey, null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));
    }

    private void stubJobAndSubmission(int attempt) {
        stubJob(attempt);
        stubSubmission(SubmissionStatus.UPLOADED, 1, "original/key.pdf");
        when(submissionRepo.transition(submissionId, SubmissionStatus.UPLOADED, SubmissionStatus.VALIDATING, 50))
            .thenReturn(1);
    }

    private ValidateJobMessage message(int fileVersion, boolean force, int attempt) {
        return new ValidateJobMessage(jobId, submissionId, examId, templateId,
            fileVersion, force, attempt);
    }

    private void stubTemplate(double w, double h) {
        when(templateRepo.findTemplateInfo(templateId))
            .thenReturn(Optional.of(new PaperTemplateRepository.TemplateInfo(templateId, w, h, true)));
    }

    private static byte[] pdfBytes(int pages, float w, float h) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(new PDRectangle(w, h)));
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void validPdf_marksJobSucceededAndPublishesAnonymizeJob() throws Exception {
        stubJobAndSubmission(0);
        stubTemplate(PAGE_W, PAGE_H);
        when(storageGateway.get("original/key.pdf")).thenReturn(pdfBytes(2, PAGE_W, PAGE_H));
        when(identityClient.createPseudonym(eq(examId), eq(submissionId), anyString()))
            .thenReturn(new PseudonymResponse(UUID.randomUUID(), "PHACH-1", submissionId));

        worker.processValidateJob(message(1, false, 0));

        // T05 only: UPLOADED → VALIDATING; T06 (ANONYMIZING) belongs to the anonymize worker.
        verify(submissionRepo).transition(submissionId, SubmissionStatus.UPLOADED, SubmissionStatus.VALIDATING, 50);
        verify(submissionRepo, never()).transition(eq(submissionId), eq(SubmissionStatus.VALIDATING),
            eq(SubmissionStatus.ANONYMIZING), anyInt());
        verify(eventRepo).append(submissionId, SubmissionStatus.VALIDATING, 50);
        verify(eventRepo, never()).append(eq(submissionId), eq(SubmissionStatus.ANONYMIZING), anyInt());
        // §8: pseudonym idempotency key is "submissionId:pseudonym"
        verify(identityClient).createPseudonym(eq(examId), eq(submissionId), eq(submissionId + ":pseudonym"));
        verify(processingJobRepo).updateJobStatus(jobId, "SUCCEEDED", 1);
        verify(jdbcTemplate).update(contains("submission_outbox"), any(), eq("ANONYMIZE"), any());
        verify(jdbcTemplate, atLeastOnce()).update(contains("sha256"), any(), eq(submissionId));
        verify(jdbcTemplate, atLeastOnce()).update(contains("page_count"), eq(2), eq(submissionId));
    }

    @Test
    void corruptPdf_marksFailedWithPdfCorrupt() {
        stubJobAndSubmission(0);
        when(storageGateway.get("original/key.pdf")).thenReturn("not-a-pdf".getBytes());
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.FAILED, 0))
            .thenReturn(1);

        worker.processValidateJob(message(1, false, 0));

        verify(submissionRepo).transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.FAILED, 0);
        verify(jdbcTemplate).update(contains("failure_code"), eq("PDF_CORRUPT"), eq(submissionId));
        verify(processingJobRepo).updateJobError(eq(jobId), eq("PDF_CORRUPT"), anyString(), isNull());
        verify(identityClient, never()).createPseudonym(any(), any(), any());
        verify(rabbitTemplate, never()).convertAndSend(eq("submission.processing"), eq("anonymize"), any(AnonymizeJobMessage.class));
    }

    @Test
    void pageCountOutOfRange_marksFailedWithPageCountInvalid() throws Exception {
        stubJobAndSubmission(0);
        when(storageGateway.get("original/key.pdf")).thenReturn(pdfBytes(21, PAGE_W, PAGE_H));
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.FAILED, 0))
            .thenReturn(1);

        worker.processValidateJob(message(1, false, 0));

        verify(jdbcTemplate).update(contains("failure_code"), eq("PAGE_COUNT_INVALID"), eq(submissionId));
        verify(submissionRepo).transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.FAILED, 0);
    }

    @Test
    void sizeMismatch_marksReviewRequiredAndCreatesReview() throws Exception {
        stubJobAndSubmission(0);
        stubTemplate(500.0, 700.0); // differs >5% from 595x842
        when(storageGateway.get("original/key.pdf")).thenReturn(pdfBytes(1, PAGE_W, PAGE_H));
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.REVIEW_REQUIRED, 0))
            .thenReturn(1);

        worker.processValidateJob(message(1, false, 0));

        verify(submissionRepo).transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.REVIEW_REQUIRED, 0);
        verify(resultClient).createReview(eq(submissionId), eq(examId), eq("TEMPLATE_SIZE_MISMATCH"), anyString(), anyString());
        verify(identityClient, never()).createPseudonym(any(), any(), any());
    }

    @Test
    void sizeMismatch_withForceFlag_skipsSizeCheck() throws Exception {
        stubJobAndSubmission(0);
        stubTemplate(500.0, 700.0);
        when(storageGateway.get("original/key.pdf")).thenReturn(pdfBytes(1, PAGE_W, PAGE_H));

        worker.processValidateJob(message(1, true, 0));

        verify(processingJobRepo).updateJobStatus(jobId, "SUCCEEDED", 1);
        verify(jdbcTemplate).update(contains("submission_outbox"), any(), eq("ANONYMIZE"), any());
    }

    @Test
    void technicalError_firstAttempt_retriesVia30sQueue() {
        stubJobAndSubmission(0);
        when(storageGateway.get("original/key.pdf")).thenThrow(new RuntimeException("minio down"));

        worker.processValidateJob(message(1, false, 0));

        verify(processingJobRepo).updateJobError(eq(jobId), eq("TECHNICAL_ERROR"), anyString(), any(Instant.class));
        verify(processingJobRepo).updateJobStatus(jobId, "QUEUED", 1);
        verify(rabbitTemplate).convertAndSend(eq("submission.validate.retry.30s"),
            any(ValidateJobMessage.class), any(MessagePostProcessor.class));
        verify(submissionRepo, never()).transition(eq(submissionId), eq(SubmissionStatus.VALIDATING), eq(SubmissionStatus.FAILED), anyInt());
    }

    @Test
    void technicalError_retryExhausted_marksFailed() {
        stubJobAndSubmission(3);
        when(storageGateway.get("original/key.pdf")).thenThrow(new RuntimeException("minio down"));
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.FAILED, 0))
            .thenReturn(1);

        worker.processValidateJob(message(1, false, 3));

        verify(processingJobRepo).updateJobError(eq(jobId), eq("VALIDATE_RETRY_EXHAUSTED"), anyString(), isNull());
        verify(processingJobRepo).updateJobStatus(jobId, "FAILED", 3);
        verify(jdbcTemplate).update(contains("failure_code"), eq("VALIDATE_RETRY_EXHAUSTED"), eq(submissionId));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), any(ValidateJobMessage.class), any(MessagePostProcessor.class));
    }

    @Test
    void missingObjectKey_retryExhausted_marksFailed() {
        stubJob(3);
        stubSubmission(SubmissionStatus.UPLOADED, 1, null);
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.FAILED, 0))
            .thenReturn(1);

        worker.processValidateJob(message(1, false, 3));

        verify(jdbcTemplate).update(contains("failure_code"), eq("VALIDATE_RETRY_EXHAUSTED"), eq(submissionId));
        verify(processingJobRepo).updateJobStatus(jobId, "FAILED", 3);
    }

    @Test
    void alreadySucceededJob_isSkipped() {
        when(processingJobRepo.findJobById(jobId))
            .thenReturn(Optional.of(new ProcessingJobRepository.JobInfo(
                jobId, submissionId, "VALIDATE", "SUCCEEDED", 1, null, null, null, 1, false, false, templateId)));

        worker.processValidateJob(message(1, false, 1));

        verify(submissionRepo, never()).findById(any());
        verify(storageGateway, never()).get(anyString());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void staleFileVersion_isAckedWithoutChanges() {
        stubJob(0);
        stubSubmission(SubmissionStatus.UPLOADED, 2, "original/key.pdf"); // current = 2, message = 1

        worker.processValidateJob(message(1, false, 0));

        verify(processingJobRepo, never()).updateJobStatus(any(), anyString(), anyInt());
        verify(processingJobRepo, never()).updateJobError(any(), any(), any(), any());
        verify(submissionRepo, never()).transition(any(), any(), any(), anyInt());
        verify(storageGateway, never()).get(anyString());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void unknownSchemaVersion_goesToDlq() {
        ValidateJobMessage bad = new ValidateJobMessage(
            ValidateJobMessage.SCHEMA_VERSION + 1, jobId, submissionId, examId, templateId,
            1, false, 0, UUID.randomUUID());

        worker.processValidateJob(bad);

        verify(rabbitTemplate).convertAndSend(eq("submission.validate.dlq"), eq(bad));
        verify(processingJobRepo, never()).findJobById(any());
    }

    @Test
    void jobSubmissionMismatch_goesToDlq() {
        UUID otherSubmission = UUID.randomUUID();
        when(processingJobRepo.findJobById(jobId))
            .thenReturn(Optional.of(new ProcessingJobRepository.JobInfo(
                jobId, otherSubmission, "VALIDATE", "QUEUED", 0, null, null, null, 1, false, false, templateId)));

        worker.processValidateJob(message(1, false, 0));

        verify(rabbitTemplate).convertAndSend(eq("submission.validate.dlq"), any(ValidateJobMessage.class));
    }
}
