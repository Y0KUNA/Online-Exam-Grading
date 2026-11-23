package vn.edu.examgrading.submission.service;

import java.util.Map;
import java.util.UUID;
import vn.edu.examgrading.submission.dto.CompleteSubmissionResponse;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;
import vn.edu.examgrading.submission.dto.SubmissionPageResponse;
import vn.edu.examgrading.submission.dto.UpdatePseudonymRequest;

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

    /**
     * Cursor-paginated exam submission list (spec D-06): {@code status} filters by
     * {@link vn.edu.examgrading.submission.domain.SubmissionStatus} name, {@code cursor}
     * is the opaque token from a previous {@code nextCursor}, {@code limit} defaults 50
     * and clamps at 200.
     */
    SubmissionPageResponse listForExam(UUID examId, String status, String cursor, Integer limit);

    Map<String, Object> detail(UUID submissionId);

    /**
     * EXAM_OFFICE identity join (FR-019): resolves candidate fields + pseudonym via
     * Identity and records an audit entry. Public shape is
     * {@code {candidateNumber,fullName,className,pseudonym}} — no candidateId.
     */
    Map<String, Object> identity(UUID submissionId);

    /**
     * Manual phách edit (spec 10 §4.1): only before FINALIZED; reason mandatory.
     * Delegates to Identity which records PseudonymHistory; audited locally.
     */
    Map<String, Object> updatePseudonym(UUID submissionId, UpdatePseudonymRequest request, String authorization);

    /**
     * File version list (spec §4.4): {@code {items:[{type,version,isCurrent,
     * sizeBytes,sha256?,createdAt}]}} — never exposes object keys or URLs.
     */
    Map<String, Object> files(UUID submissionId);

    CompleteSubmissionResponse complete(UUID submissionId);

    Map<String, Object> retryValidation(UUID submissionId, boolean forceTemplateSize, boolean recoverStalled);

    default Map<String, Object> retryValidation(UUID submissionId, boolean forceTemplateSize) {
        return retryValidation(submissionId, forceTemplateSize, false);
    }
}
