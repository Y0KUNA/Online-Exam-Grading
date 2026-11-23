package vn.edu.examgrading.submission.job;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.dto.PseudonymResponse;
import vn.edu.examgrading.submission.messaging.AnonymizeJobMessage;
import vn.edu.examgrading.submission.messaging.ValidationWorker.ValidateJobMessage;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository.StaleJob;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;

/**
 * Unit tests for {@link ProcessingJobReconciler} (Ticket 07 task 3): stale
 * QUEUED jobs are republished with rebuilt messages; terminal/missing
 * submissions and per-job failures are isolated.
 */
@ExtendWith(MockitoExtension.class)
class ProcessingJobReconcilerTest {

    @Mock private ProcessingJobRepository processingJobRepo;
    @Mock private SubmissionRepositoryMarker submissionRepo;
    @Mock private IdentityClient identityClient;
    @Mock private RabbitTemplate rabbitTemplate;

    private ProcessingJobReconciler reconciler;
    private final Instant now = Instant.parse("2026-02-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        reconciler = new ProcessingJobReconciler(processingJobRepo, submissionRepo,
            identityClient, rabbitTemplate, Clock.fixed(now, ZoneOffset.UTC), 10);
    }

    private Submission submission(UUID id, UUID examId, SubmissionStatus status) {
        return new Submission(id, examId, UUID.randomUUID(), status, 1, 50,
            null, 100L, null, 3, "key/" + id, null, 2, now);
    }

    private StaleJob staleJob(String step) {
        return new StaleJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            step, 1, 1, false, false, null);
    }

    @Test
    void noStaleJobs_doesNothing() {
        when(processingJobRepo.findStaleQueued(any(), any(), anyInt())).thenReturn(List.of());

        reconciler.run();

        verifyNoInteractions(rabbitTemplate, identityClient, submissionRepo);
    }

    @Test
    void staleValidate_republishesValidateMessage() {
        StaleJob job = staleJob("VALIDATE");
        Submission s = submission(job.submissionId(), job.examId(), SubmissionStatus.VALIDATING);
        when(processingJobRepo.findStaleQueued(any(), any(), eq(200))).thenReturn(List.of(job));
        when(submissionRepo.findById(job.submissionId())).thenReturn(Optional.of(s));

        reconciler.run();

        ArgumentCaptor<Object> msg = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("validate"), msg.capture());
        ValidateJobMessage m = (ValidateJobMessage) msg.getValue();
        assert m.jobId().equals(job.jobId());
        assert m.submissionId().equals(job.submissionId());
        assert m.examId().equals(job.examId());
        assert m.templateId().equals(s.templateId()); // falls back to submission's
        assert m.fileVersion() == 1;
        assert m.attempt() == 1;
        verifyNoInteractions(identityClient);
    }

    @Test
    void staleValidate_usesJobTemplateIdAndForceWhenSet() {
        UUID templateId = UUID.randomUUID();
        StaleJob job = new StaleJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "VALIDATE", 2, 3, true, false, templateId);
        Submission s = submission(job.submissionId(), job.examId(), SubmissionStatus.UPLOADED);
        when(processingJobRepo.findStaleQueued(any(), any(), anyInt())).thenReturn(List.of(job));
        when(submissionRepo.findById(job.submissionId())).thenReturn(Optional.of(s));

        reconciler.run();

        ArgumentCaptor<Object> msg = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("validate"), msg.capture());
        ValidateJobMessage m = (ValidateJobMessage) msg.getValue();
        assert m.templateId().equals(templateId);
        assert m.force();
        assert m.fileVersion() == 3;
        assert m.attempt() == 2;
    }

    @Test
    void staleAnonymize_refetchesPseudonymAndRepublishes() {
        StaleJob job = staleJob("ANONYMIZE");
        Submission s = submission(job.submissionId(), job.examId(), SubmissionStatus.ANONYMIZING);
        when(processingJobRepo.findStaleQueued(any(), any(), anyInt())).thenReturn(List.of(job));
        when(submissionRepo.findById(job.submissionId())).thenReturn(Optional.of(s));
        when(identityClient.createPseudonym(job.examId(), job.submissionId(),
            job.submissionId() + ":pseudonym"))
            .thenReturn(new PseudonymResponse(UUID.randomUUID(), "H7", job.submissionId()));

        reconciler.run();

        verify(identityClient).createPseudonym(job.examId(), job.submissionId(),
            job.submissionId() + ":pseudonym");
        ArgumentCaptor<Object> msg = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq("submission.processing"), eq("anonymize"), msg.capture());
        AnonymizeJobMessage m = (AnonymizeJobMessage) msg.getValue();
        assert m.jobId().equals(job.jobId());
        assert "H7".equals(m.pseudonym());
        assert m.attempt() == 1;
    }

    @Test
    void terminalOrMissingSubmission_isSkipped() {
        StaleJob failed = staleJob("VALIDATE");
        StaleJob gone = staleJob("ANONYMIZE");
        when(processingJobRepo.findStaleQueued(any(), any(), anyInt()))
            .thenReturn(List.of(failed, gone));
        when(submissionRepo.findById(failed.submissionId())).thenReturn(Optional.of(
            submission(failed.submissionId(), failed.examId(), SubmissionStatus.FAILED)));
        when(submissionRepo.findById(gone.submissionId())).thenReturn(Optional.empty());

        reconciler.run();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(ValidateJobMessage.class));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(AnonymizeJobMessage.class));
        verifyNoInteractions(identityClient);
    }

    @Test
    void unknownStep_isIgnored() {
        StaleJob job = staleJob("REVIEW");
        when(processingJobRepo.findStaleQueued(any(), any(), anyInt())).thenReturn(List.of(job));
        when(submissionRepo.findById(job.submissionId())).thenReturn(Optional.of(
            submission(job.submissionId(), job.examId(), SubmissionStatus.VALIDATING)));

        reconciler.run();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(ValidateJobMessage.class));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(AnonymizeJobMessage.class));
    }

    @Test
    void oneBadJob_doesNotBlockTheRest() {
        StaleJob bad = staleJob("VALIDATE");
        StaleJob good = staleJob("VALIDATE");
        when(processingJobRepo.findStaleQueued(any(), any(), anyInt()))
            .thenReturn(List.of(bad, good));
        when(submissionRepo.findById(bad.submissionId()))
            .thenThrow(new RuntimeException("db hiccup"));
        when(submissionRepo.findById(good.submissionId())).thenReturn(Optional.of(
            submission(good.submissionId(), good.examId(), SubmissionStatus.VALIDATING)));

        reconciler.run();

        verify(rabbitTemplate, times(1)).convertAndSend(
            eq("submission.processing"), eq("validate"), any(ValidateJobMessage.class));
    }
}
