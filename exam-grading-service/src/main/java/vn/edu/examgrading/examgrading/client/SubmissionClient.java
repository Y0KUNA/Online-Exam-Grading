package vn.edu.examgrading.examgrading.client;
import java.util.Map;
import java.util.UUID;
/** Signed internal client to Submission; never accesses its database. */
public interface SubmissionClient {
    Map<String, Object> anonymizedAccess(UUID submissionId);

    /** Mirrors the grading lifecycle in Submission, which is the office read model. */
    Map<String, Object> changeStatus(UUID submissionId, String newStatus, int progress);
}
