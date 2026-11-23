package vn.edu.examgrading.submission.messaging;

import java.io.ByteArrayInputStream;
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
import vn.edu.examgrading.submission.anonymize.PageMasker;
import vn.edu.examgrading.submission.client.ResultClient;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.S3ObjectStorage;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for {@link AnonymizationWorker} (TICKET 03, spec §7/§8).
 * Uses a REAL MinIO object via {@link S3ObjectStorage} at localhost:9000
 * (requires Compose MinIO from TICKET 01); repositories/JDBC are mocked.
 * Verifies the ANONYMIZED object lands in the {@code anonymized} bucket under
 * {@code {examId}/{submissionId}/v{n}.pdf} and is a valid multi-page PDF.
 */
@ExtendWith(MockitoExtension.class)
class AnonymizationWorkerMinioTest {

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;
    private static final String ANONYMIZED_BUCKET = "anonymized";

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private SubmissionRepositoryMarker submissionRepo;
    @Mock private ProcessingJobRepository processingJobRepo;
    @Mock private PaperTemplateRepository templateRepo;
    @Mock private ExamFileRepository examFileRepo;
    @Mock private SubmissionEventRepository eventRepo;
    @Mock private ResultClient resultClient;
    @Mock private JdbcTemplate jdbcTemplate;

    private S3ObjectStorage storage;
    private AnonymizationWorker worker;

    private final UUID examId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private String objectKey;
    private String anonymizedKey;

    private final PaperTemplateRepository.MaskRegions regions = new PaperTemplateRepository.MaskRegions(
        new PaperTemplateRepository.Region(0.10, 0.10, 0.50, 0.30),
        new PaperTemplateRepository.Region(0.15, 0.15, 0.30, 0.10),
        "Helvetica", 14.0, "CENTER");

    @BeforeEach
    void setUp() {
        // .env's S3_INTERNAL_ENDPOINT is the docker-internal hostname; from the
        // host the published port is localhost:9000 (system env still wins).
        String endpoint = System.getenv("S3_INTERNAL_ENDPOINT") != null
            && !System.getenv("S3_INTERNAL_ENDPOINT").isBlank()
            ? System.getenv("S3_INTERNAL_ENDPOINT") : "http://localhost:9000";
        StorageProperties props = new StorageProperties(
            "s3",
            endpoint,
            env("S3_REGION", "us-east-1"),
            env("S3_ACCESS_KEY", "minioadmin"),
            env("S3_SECRET_KEY", "change-me"),
            env("S3_BUCKET", "original"),
            ANONYMIZED_BUCKET,
            env("S3_PUBLIC_BASE", "http://localhost:8080/storage"),
            52428800L, 1024L);
        storage = S3ObjectStorage.create(props, Clock.systemUTC());
        worker = new AnonymizationWorker(
            rabbitTemplate, storage, submissionRepo, processingJobRepo,
            templateRepo, examFileRepo, eventRepo, resultClient, props,
            new PageMasker(200, 100, 40_000_000L, 14400, 120),
            jdbcTemplate, Clock.systemUTC());

        objectKey = "integration/" + submissionId + "/v1.pdf";
        anonymizedKey = examId + "/" + submissionId + "/v1.pdf";
        when(processingJobRepo.findJobById(jobId))
            .thenReturn(Optional.of(new ProcessingJobRepository.JobInfo(
                jobId, submissionId, "ANONYMIZE", "QUEUED", 0,
                null, null, null, 1, false, false, templateId)));
    }

    @AfterEach
    void tearDown() {
        storage.delete(objectKey);
        storage.delete(ANONYMIZED_BUCKET, anonymizedKey);
    }

    private void stubSubmissionAndFile() {
        Submission submission = new Submission(
            submissionId, examId, templateId, SubmissionStatus.VALIDATING,
            1, 0, null, 1024L, null, null, objectKey, null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));
        when(examFileRepo.findCurrentOriginal(submissionId))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 1, 0, objectKey)));
        when(templateRepo.findMaskRegions(templateId)).thenReturn(Optional.of(regions));
        when(submissionRepo.transition(submissionId, SubmissionStatus.VALIDATING,
            SubmissionStatus.ANONYMIZING, 75)).thenReturn(1);
        when(submissionRepo.transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.READY_FOR_GRADING, 100)).thenReturn(1);
    }

    private AnonymizeJobMessage message() {
        return new AnonymizeJobMessage(jobId, submissionId, examId, templateId, 1, "PH-001");
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
    void realMinioObject_anonymizesAndStoresInAnonymizedBucket() throws Exception {
        byte[] pdf = pdfBytes(2);
        storage.put(objectKey, pdf, "application/pdf");
        stubSubmissionAndFile();

        worker.processAnonymizeJob(message());

        verify(submissionRepo).transition(submissionId, SubmissionStatus.VALIDATING,
            SubmissionStatus.ANONYMIZING, 75);
        verify(eventRepo).append(submissionId, SubmissionStatus.ANONYMIZING, 75);
        verify(processingJobRepo).updateJobStatus(jobId, "SUCCEEDED", 1);

        // The ANONYMIZED object must exist in the anonymized bucket under the
        // deterministic key and be a readable PDF with both pages preserved.
        var stored = storage.head(ANONYMIZED_BUCKET, anonymizedKey);
        assertTrue(stored.isPresent(), "ANONYMIZED object missing in bucket " + ANONYMIZED_BUCKET);
        assertTrue(stored.get().sizeBytes() > 0);
        byte[] anonymized = storage.get(ANONYMIZED_BUCKET, anonymizedKey);
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(anonymized))) {
            assertEquals(2, doc.getNumberOfPages());
        }
        verify(examFileRepo).insertAnonymized(any(), eq(submissionId), eq(1),
            eq((long) anonymized.length), eq(anonymizedKey), anyString());
        verify(submissionRepo).transition(submissionId, SubmissionStatus.ANONYMIZING,
            SubmissionStatus.READY_FOR_GRADING, 100);
        verify(eventRepo).append(submissionId, SubmissionStatus.READY_FOR_GRADING, 100);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) return value;
        try {
            java.nio.file.Path envFile = java.nio.file.Path.of("..", ".env");
            if (java.nio.file.Files.exists(envFile)) {
                for (String line : java.nio.file.Files.readAllLines(envFile)) {
                    if (line.startsWith(key + "=")) {
                        String v = line.substring(line.indexOf('=') + 1).trim();
                        if (!v.isBlank()) return v;
                    }
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }
}
