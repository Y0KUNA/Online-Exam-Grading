package vn.edu.examgrading.submission.job;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway.ObjectInfo;
import vn.edu.examgrading.submission.storage.StorageUnavailableException;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

/**
 * Unit tests for {@link OrphanObjectCleanupJob} (Ticket 07 task 2): only
 * objects older than the grace period with no exam_file and no live
 * UPLOADING reference are deleted; failures are isolated per object.
 */
@ExtendWith(MockitoExtension.class)
class OrphanObjectCleanupJobTest {

    @Mock private ObjectStorageGateway gateway;
    @Mock private ExamFileRepository examFileRepo;
    @Mock private SubmissionRepositoryMarker submissionRepo;

    private OrphanObjectCleanupJob job;
    private final Instant now = Instant.parse("2026-02-01T12:00:00Z");
    private final StorageProperties props = new StorageProperties(
        "s3", "http://minio:9000", "us-east-1", "k", "s",
        "original", "anonymized", "http://gw/storage", 52428800L, 1024L);

    @BeforeEach
    void setUp() {
        job = new OrphanObjectCleanupJob(gateway, props, examFileRepo, submissionRepo,
            Clock.fixed(now, ZoneOffset.UTC), 60);
    }

    private ObjectInfo object(String key, Instant modified) {
        return new ObjectInfo(key, modified, 100L);
    }

    private Instant old() { return now.minus(Duration.ofHours(2)); }
    private Instant fresh() { return now.minus(Duration.ofMinutes(10)); }

    @Test
    void oldUnreferencedObject_isDeleted() {
        when(gateway.list("original")).thenReturn(List.of(object("orphan/key.pdf", old())));
        when(examFileRepo.existsByObjectKey("orphan/key.pdf")).thenReturn(false);
        when(submissionRepo.existsUploadingWithObjectKey("orphan/key.pdf")).thenReturn(false);

        job.run();

        verify(gateway).delete("original", "orphan/key.pdf");
    }

    @Test
    void youngObject_isKeptEvenIfUnreferenced() {
        when(gateway.list("original")).thenReturn(List.of(object("fresh/key.pdf", fresh())));

        job.run();

        verify(gateway, never()).delete(any(), any());
        verifyNoInteractions(examFileRepo, submissionRepo);
    }

    @Test
    void objectReferencedByExamFile_isKept() {
        when(gateway.list("original")).thenReturn(List.of(object("live/key.pdf", old())));
        when(examFileRepo.existsByObjectKey("live/key.pdf")).thenReturn(true);

        job.run();

        verify(gateway, never()).delete(any(), any());
        verify(submissionRepo, never()).existsUploadingWithObjectKey(any());
    }

    @Test
    void objectReferencedByUploadingSubmission_isKept() {
        when(gateway.list("original")).thenReturn(List.of(object("pending/key.pdf", old())));
        when(examFileRepo.existsByObjectKey("pending/key.pdf")).thenReturn(false);
        when(submissionRepo.existsUploadingWithObjectKey("pending/key.pdf")).thenReturn(true);

        job.run();

        verify(gateway, never()).delete(any(), any());
    }

    @Test
    void nullLastModified_isKept() {
        when(gateway.list("original")).thenReturn(List.of(object("unknown/key.pdf", null)));

        job.run();

        verify(gateway, never()).delete(any(), any());
    }

    @Test
    void listFailure_abortsRunWithoutThrowing() {
        when(gateway.list("original")).thenThrow(new StorageUnavailableException("minio down"));

        job.run();

        verify(gateway, never()).delete(any(), any());
    }

    @Test
    void oneBadObject_doesNotBlockTheRest() {
        when(gateway.list("original")).thenReturn(List.of(
            object("bad/key.pdf", old()), object("good/key.pdf", old())));
        when(examFileRepo.existsByObjectKey("bad/key.pdf"))
            .thenThrow(new RuntimeException("db hiccup"));
        when(examFileRepo.existsByObjectKey("good/key.pdf")).thenReturn(false);
        when(submissionRepo.existsUploadingWithObjectKey("good/key.pdf")).thenReturn(false);

        job.run();

        verify(gateway, never()).delete("original", "bad/key.pdf");
        verify(gateway).delete("original", "good/key.pdf");
    }
}
