package vn.edu.examgrading.submission.messaging;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.client.ResultClient;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.messaging.ValidationWorker.ValidateJobMessage;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.S3ObjectStorage;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for {@link ValidationWorker} (TICKET 02, spec §8).
 * Uses a REAL MinIO object via {@link S3ObjectStorage} at localhost:9000
 * (requires Compose MinIO from TICKET 01); repositories/JDBC are mocked.
 */
@ExtendWith(MockitoExtension.class)
class ValidationWorkerMinioTest {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private SubmissionRepositoryMarker submissionRepo;
    @Mock private ProcessingJobRepository processingJobRepo;
    @Mock private PaperTemplateRepository templateRepo;
    @Mock private vn.edu.examgrading.submission.repository.ExamFileRepository examFileRepo;
    @Mock private SubmissionEventRepository eventRepo;
    @Mock private IdentityClient identityClient;
    @Mock private ResultClient resultClient;
    @Mock private JdbcTemplate jdbcTemplate;

    private S3ObjectStorage storage;
    private ValidationWorker worker;

    private final UUID examId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private String objectKey;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties(
            "s3",
            env("S3_INTERNAL_ENDPOINT", "http://localhost:9000"),
            env("S3_REGION", "us-east-1"),
            env("S3_ACCESS_KEY", "minioadmin"),
            env("S3_SECRET_KEY", "change-me"),
            env("S3_BUCKET", "original"),
            "anonymized",
            env("S3_PUBLIC_BASE", "http://localhost:8080/storage"),
            52428800L, 1024L);
        storage = S3ObjectStorage.create(props, Clock.systemUTC());
        worker = new ValidationWorker(
            rabbitTemplate, storage, submissionRepo, processingJobRepo,
            templateRepo, examFileRepo, eventRepo, identityClient, resultClient, jdbcTemplate, Clock.systemUTC());

        objectKey = "integration/" + submissionId + "/v1.pdf";
        when(processingJobRepo.findJobById(jobId))
            .thenReturn(Optional.of(new ProcessingJobRepository.JobInfo(
                jobId, submissionId, "VALIDATE", "QUEUED", 0, null, null, null, 1, false, false, templateId)));
    }

    @AfterEach
    void tearDown() {
        storage.delete(objectKey);
    }

    private void stubSubmission() {
        Submission submission = new Submission(
            submissionId, examId, templateId, SubmissionStatus.UPLOADED,
            1, 0, null, 1024L, null, null, objectKey, null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));
        when(examFileRepo.findCurrentOriginal(submissionId))
            .thenReturn(Optional.of(new vn.edu.examgrading.submission.repository.ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 1, 1024L, objectKey)));
        // T05: UPLOADED → VALIDATING on receipt — stubbed so strict stubbing does
        // not flag the FAILED transition stub below as an argument mismatch.
        when(submissionRepo.transition(submissionId, SubmissionStatus.UPLOADED, SubmissionStatus.VALIDATING, 50))
            .thenReturn(1);
    }

    private ValidateJobMessage message() {
        return new ValidateJobMessage(jobId, submissionId, examId, templateId, 1, false, 0);
    }

    private static byte[] pdfBytes(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void realMinioObject_validPdf_completesValidationAndPublishesAnonymize() throws Exception {
        byte[] pdf = pdfBytes(3);
        storage.put(objectKey, pdf, "application/pdf");
        stubSubmission();
        when(templateRepo.findTemplateInfo(templateId))
            .thenReturn(Optional.of(new PaperTemplateRepository.TemplateInfo(templateId, PAGE_W, PAGE_H, true)));

        worker.processValidateJob(message());

        verify(submissionRepo).transition(submissionId, SubmissionStatus.UPLOADED, SubmissionStatus.VALIDATING, 50);
        verify(submissionRepo, never()).transition(eq(submissionId), eq(SubmissionStatus.VALIDATING),
            eq(SubmissionStatus.ANONYMIZING), anyInt());
        verify(jdbcTemplate, atLeastOnce()).update(contains("page_count"), eq(3), eq(submissionId));
        verify(identityClient).createPseudonym(eq(examId), eq(submissionId), eq(submissionId + ":pseudonym"));
        verify(processingJobRepo).updateJobStatus(jobId, "SUCCEEDED", 1);
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("anonymize"),
            any(AnonymizeJobMessage.class));
    }

    @Test
    void realMinioObject_corruptBytes_marksFailedPdfCorrupt() {
        storage.put(objectKey, "garbage-not-a-pdf".getBytes(), "application/pdf");
        stubSubmission();
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.FAILED, 0))
            .thenReturn(1);

        worker.processValidateJob(message());

        verify(jdbcTemplate).update(contains("failure_code"), eq("PDF_CORRUPT"), eq(submissionId));
        verify(submissionRepo).transition(submissionId, SubmissionStatus.VALIDATING, SubmissionStatus.FAILED, 0);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
