package vn.edu.examgrading.submission.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.edu.examgrading.submission.dto.CompleteSubmissionResponse;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;

/**
 * Application-service contract for the upload slice. Kept under its original marker
 * name (TICKET 00) but now carrying the real use-cases; the JDBC-backed implementation
 * is wired in TICKET 04. Implementations must not leak persistence/HTTP types.
 */
public interface SubmissionServiceMarker {

    InitSubmissionResponse init(UUID examId, InitSubmissionRequest request, UUID actorId, String authorization);

    default InitSubmissionResponse init(UUID examId, InitSubmissionRequest request, UUID actorId) {
        return init(examId, request, actorId, null);
    }

    List<Map<String, Object>> listForExam(UUID examId);

    Map<String, Object> detail(UUID submissionId);

    CompleteSubmissionResponse complete(UUID submissionId);
}
