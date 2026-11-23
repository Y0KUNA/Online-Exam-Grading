package vn.edu.examgrading.submission.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway.ObjectInfo;
import vn.edu.examgrading.submission.storage.StorageUnavailableException;
import vn.edu.examgrading.submission.storage.config.StorageProperties;

/**
 * Orphan object sweeper (Ticket 07 task 2, spec §9 ops): hourly, lists the
 * {@code original} bucket and deletes objects older than the grace period that
 * no {@code exam_file} row references. Two extra guards keep live uploads safe:
 * objects younger than {@code app.jobs.orphan-grace-minutes} (default 60) are
 * never touched, and keys still referenced by an UPLOADING submission are kept
 * (an in-flight or about-to-complete upload has no exam_file row yet).
 * Everything else — abandoned uploads (FAILED), FILE_TOO_LARGE leftovers,
 * foreign writes — is deleted. Per-object failures are isolated.
 */
@Component
public class OrphanObjectCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanObjectCleanupJob.class);

    private final ObjectStorageGateway gateway;
    private final StorageProperties props;
    private final ExamFileRepository examFileRepo;
    private final SubmissionRepositoryMarker submissionRepo;
    private final Clock clock;
    private final Duration grace;

    public OrphanObjectCleanupJob(
            ObjectStorageGateway gateway,
            StorageProperties props,
            ExamFileRepository examFileRepo,
            SubmissionRepositoryMarker submissionRepo,
            Clock clock,
            @Value("${app.jobs.orphan-grace-minutes:60}") long graceMinutes) {
        this.gateway = gateway;
        this.props = props;
        this.examFileRepo = examFileRepo;
        this.submissionRepo = submissionRepo;
        this.clock = clock;
        this.grace = Duration.ofMinutes(graceMinutes);
    }

    @Scheduled(cron = "${app.jobs.orphan-cleanup-cron:0 0 * * * *}")
    public void run() {
        List<ObjectInfo> objects;
        try {
            objects = gateway.list(props.bucket());
        } catch (StorageUnavailableException e) {
            log.warn("Orphan cleanup: cannot list bucket {}: {}", props.bucket(), e.toString());
            return;
        }
        Instant cutoff = Instant.now(clock).minus(grace);
        int deleted = 0;
        for (ObjectInfo object : objects) {
            try {
                if (isOrphan(object, cutoff)) {
                    gateway.delete(props.bucket(), object.objectKey());
                    deleted++;
                }
            } catch (RuntimeException e) {
                log.warn("Orphan cleanup failed for {}: {}", object.objectKey(), e.toString());
            }
        }
        if (deleted > 0) {
            log.info("Orphan cleanup: deleted {} object(s) from {}", deleted, props.bucket());
        }
    }

    private boolean isOrphan(ObjectInfo object, Instant cutoff) {
        // Unknown/clock-skewed timestamps are treated as too fresh — never delete.
        if (object.lastModified() == null || object.lastModified().isAfter(cutoff)) {
            return false;
        }
        if (examFileRepo.existsByObjectKey(object.objectKey())) {
            return false;
        }
        return !submissionRepo.existsUploadingWithObjectKey(object.objectKey());
    }
}
