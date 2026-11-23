package vn.edu.examgrading.submission.job;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;

/**
 * Unit tests for {@link UploadCleanupJob} (Ticket 07 task 1): expired
 * UPLOADING submissions move to FAILED/UPLOAD_ABANDONED with event, audit
 * and Identity binding release; races and per-row failures are isolated.
 */
@ExtendWith(MockitoExtension.class)
class UploadCleanupJobTest {

    @Mock private SubmissionRepositoryMarker submissionRepo;
    @Mock private SubmissionEventRepository eventRepo;
    @Mock private IdentityClient identityClient;
    @Mock private JdbcTemplate jdbcTemplate;

    private UploadCleanupJob job;
    private final Instant now = Instant.parse("2026-02-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        job = new UploadCleanupJob(submissionRepo, eventRepo, identityClient,
            jdbcTemplate, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Submission expired(UUID id) {
        return new Submission(id, UUID.randomUUID(), UUID.randomUUID(),
            SubmissionStatus.UPLOADING, 1, 0,
            now.minusSeconds(60), 100L, null, null, "key/" + id, null, 0, now.minusSeconds(3600));
    }

    @Test
    void expiredUploading_failsWithCodeAppendsEventAuditsAndReleasesBinding() {
        UUID id = UUID.randomUUID();
        Submission s = expired(id);
        when(submissionRepo.findExpiredUploading(now)).thenReturn(List.of(s));
        when(submissionRepo.failUploading(id, "UPLOAD_ABANDONED")).thenReturn(1);

        job.run();

        verify(submissionRepo).failUploading(id, "UPLOAD_ABANDONED");
        verify(eventRepo).append(id, SubmissionStatus.FAILED, 0);
        verify(jdbcTemplate).update(contains("SUBMISSION_STATUS_CHANGED"), any(), eq(id), contains("UPLOAD_ABANDONED"));
        verify(identityClient).releaseBinding(id, "UPLOAD_ABANDONED");
    }

    @Test
    void noExpiredRows_doesNothing() {
        when(submissionRepo.findExpiredUploading(now)).thenReturn(List.of());

        job.run();

        verify(submissionRepo, never()).failUploading(any(), any());
        verifyNoInteractions(eventRepo, identityClient, jdbcTemplate);
    }

    @Test
    void racedOutOfUploading_skipsEventAuditAndRelease() {
        UUID id = UUID.randomUUID();
        when(submissionRepo.findExpiredUploading(now)).thenReturn(List.of(expired(id)));
        when(submissionRepo.failUploading(id, "UPLOAD_ABANDONED")).thenReturn(0);

        job.run();

        verify(eventRepo, never()).append(any(), any(), anyInt());
        verifyNoInteractions(identityClient, jdbcTemplate);
    }

    @Test
    void bindingReleaseFailure_doesNotFailTheRow() {
        UUID id = UUID.randomUUID();
        when(submissionRepo.findExpiredUploading(now)).thenReturn(List.of(expired(id)));
        when(submissionRepo.failUploading(id, "UPLOAD_ABANDONED")).thenReturn(1);
        doThrow(new RuntimeException("identity down"))
            .when(identityClient).releaseBinding(id, "UPLOAD_ABANDONED");

        job.run(); // must not throw

        verify(eventRepo).append(id, SubmissionStatus.FAILED, 0);
        verify(jdbcTemplate).update(contains("SUBMISSION_STATUS_CHANGED"), any(), eq(id), any());
    }

    @Test
    void oneBadRow_doesNotBlockTheRest() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(submissionRepo.findExpiredUploading(now))
            .thenReturn(List.of(expired(bad), expired(good)));
        when(submissionRepo.failUploading(bad, "UPLOAD_ABANDONED"))
            .thenThrow(new RuntimeException("db hiccup"));
        when(submissionRepo.failUploading(good, "UPLOAD_ABANDONED")).thenReturn(1);

        job.run();

        verify(eventRepo).append(good, SubmissionStatus.FAILED, 0);
        verify(identityClient).releaseBinding(good, "UPLOAD_ABANDONED");
        verify(identityClient, never()).releaseBinding(eq(bad), any());
    }
}
