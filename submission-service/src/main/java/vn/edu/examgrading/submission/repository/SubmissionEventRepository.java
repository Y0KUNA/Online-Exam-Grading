package vn.edu.examgrading.submission.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.edu.examgrading.submission.domain.SubmissionStatus;

/** Append-only submission event log contract (impl in TICKET 03). */
public interface SubmissionEventRepository {

    void append(UUID submissionId, SubmissionStatus status, int progress);

    /** Events of one exam after {@code afterEventId}, oldest first (SSE replay/poll). */
    List<EventRow> findByExamAfter(UUID examId, long afterEventId, int limit);

    record EventRow(long eventId, UUID submissionId, SubmissionStatus status,
            int progress, Instant updatedAt) {}
}
