package vn.edu.examgrading.submission.repository;

import java.util.UUID;
import vn.edu.examgrading.submission.domain.SubmissionStatus;

/** Append-only submission event log contract (impl in TICKET 03). */
public interface SubmissionEventRepository {

    void append(UUID submissionId, SubmissionStatus status, int progress);
}
