package vn.edu.examgrading.submission.job;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.repository.SubmissionEventRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;


@Component
public class UploadCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(UploadCleanupJob.class);
    private static final String FAILURE_CODE = "UPLOAD_ABANDONED";

    private final SubmissionRepositoryMarker submissionRepo;
    private final SubmissionEventRepository eventRepo;
    private final IdentityClient identityClient;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public UploadCleanupJob(
            SubmissionRepositoryMarker submissionRepo,
            SubmissionEventRepository eventRepo,
            IdentityClient identityClient,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.submissionRepo = submissionRepo;
        this.eventRepo = eventRepo;
        this.identityClient = identityClient;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.jobs.upload-cleanup-cron:0 */5 * * * *}")
    public void run() {
        List<Submission> expired = submissionRepo.findExpiredUploading(Instant.now(clock));
        if (expired.isEmpty()) {
            return;
        }
        log.info("Upload cleanup: {} expired UPLOADING submission(s)", expired.size());
        for (Submission submission : expired) {
            try {
                abandon(submission);
            } catch (RuntimeException e) {
                log.warn("Upload cleanup failed for submissionId={}: {}", submission.submissionId(), e.toString());
            }
        }
    }

    private void abandon(Submission submission) {
        UUID submissionId = submission.submissionId();
        int moved = submissionRepo.failUploading(submissionId, FAILURE_CODE);
        if (moved == 0) {
            // Raced with complete() or a retry of this job — nothing to do.
            return;
        }
        eventRepo.append(submissionId, SubmissionStatus.FAILED, 0);
        jdbcTemplate.update(
            "insert into audit_log(id,actor_id,action,entity_type,entity_id,detail) values (?,null,'SUBMISSION_STATUS_CHANGED','submission',?,?::jsonb)",
            UUID.randomUUID(), submissionId,
            "{\"from\":\"UPLOADING\",\"to\":\"FAILED\",\"reasonCode\":\"" + FAILURE_CODE + "\"}");
        try {
            identityClient.releaseBinding(submissionId, FAILURE_CODE);
        } catch (RuntimeException e) {
            // Binding release is best-effort here: the submission is already
            // FAILED, and Identity's release endpoint is idempotent so a later
            // sweep/manual call can finish the job.
            log.warn("Binding release failed for submissionId={}: {}", submissionId, e.toString());
        }
        log.info("Submission abandoned: submissionId={}, examId={}", submissionId, submission.examId());
    }
}
