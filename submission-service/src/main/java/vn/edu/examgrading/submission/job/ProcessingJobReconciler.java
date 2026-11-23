package vn.edu.examgrading.submission.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.edu.examgrading.submission.client.IdentityClient;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.messaging.AnonymizeJobMessage;
import vn.edu.examgrading.submission.messaging.ValidationWorker.ValidateJobMessage;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository;
import vn.edu.examgrading.submission.repository.ProcessingJobRepository.StaleJob;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;

/**
 * Processing-job reconciler (Ticket 07 task 3, spec §8): every 5 minutes,
 * republish QUEUED jobs untouched for {@code app.jobs.reconcile-stale-minutes}
 * (default 10) whose scheduled retry time has passed — covers messages lost
 * between the DB commit and the broker (or a broker outage). Both VALIDATE and
 * ANONYMIZE steps are rebuilt from the job row + submission, never trusting a
 * stale payload: the pseudonym is re-fetched through Identity's deterministic
 * idempotency key, exactly as ValidationWorker does. Workers stay idempotent
 * on jobId, so duplicate deliveries are safe; terminal submissions
 * (FAILED/FINALIZED) are skipped to avoid noise.
 */
@Component
public class ProcessingJobReconciler {

    private static final Logger log = LoggerFactory.getLogger(ProcessingJobReconciler.class);
    private static final String EXCHANGE = "submission.processing";
    private static final int SWEEP_LIMIT = 200;

    private final ProcessingJobRepository processingJobRepo;
    private final SubmissionRepositoryMarker submissionRepo;
    private final IdentityClient identityClient;
    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;
    private final Duration staleAfter;

    public ProcessingJobReconciler(
            ProcessingJobRepository processingJobRepo,
            SubmissionRepositoryMarker submissionRepo,
            IdentityClient identityClient,
            RabbitTemplate rabbitTemplate,
            Clock clock,
            @Value("${app.jobs.reconcile-stale-minutes:10}") long staleMinutes) {
        this.processingJobRepo = processingJobRepo;
        this.submissionRepo = submissionRepo;
        this.identityClient = identityClient;
        this.rabbitTemplate = rabbitTemplate;
        this.clock = clock;
        this.staleAfter = Duration.ofMinutes(staleMinutes);
    }

    @Scheduled(cron = "${app.jobs.reconcile-cron:0 */5 * * * *}")
    public void run() {
        Instant now = Instant.now(clock);
        List<StaleJob> stale = processingJobRepo.findStaleQueued(
            now.minus(staleAfter), now, SWEEP_LIMIT);
        if (stale.isEmpty()) {
            return;
        }
        log.info("Job reconciler: {} stale QUEUED job(s)", stale.size());
        for (StaleJob job : stale) {
            try {
                republish(job);
            } catch (RuntimeException e) {
                log.warn("Reconciler failed for jobId={}: {}", job.jobId(), e.toString());
            }
        }
    }

    private void republish(StaleJob job) {
        var submission = submissionRepo.findById(job.submissionId()).orElse(null);
        if (submission == null
                || submission.status() == SubmissionStatus.FAILED
                || submission.status() == SubmissionStatus.FINALIZED) {
            return;
        }
        UUID templateId = job.templateId() != null ? job.templateId() : submission.templateId();
        switch (job.step()) {
            case "VALIDATE" -> rabbitTemplate.convertAndSend(EXCHANGE, "validate",
                new ValidateJobMessage(ValidateJobMessage.SCHEMA_VERSION, job.jobId(),
                    job.submissionId(), job.examId(), templateId, job.fileVersion(),
                    job.force(), job.attempt(), UUID.randomUUID()));
            case "ANONYMIZE" -> {
                // Same deterministic idempotency key ValidationWorker used —
                // Identity returns the existing pseudonym on replay.
                var pseudonym = identityClient.createPseudonym(
                    job.examId(), job.submissionId(), job.submissionId() + ":pseudonym");
                rabbitTemplate.convertAndSend(EXCHANGE, "anonymize",
                    new AnonymizeJobMessage(AnonymizeJobMessage.SCHEMA_VERSION, job.jobId(),
                        job.submissionId(), job.examId(), templateId, job.fileVersion(),
                        pseudonym == null ? null : pseudonym.pseudonym(),
                        job.attempt(), UUID.randomUUID()));
            }
            default -> log.warn("Reconciler: unknown step {} for jobId={}", job.step(), job.jobId());
        }
        log.info("Reconciler republished jobId={} step={} submissionId={}",
            job.jobId(), job.step(), job.submissionId());
    }
}
