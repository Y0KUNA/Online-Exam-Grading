package vn.edu.examgrading.submission.service;

import java.util.Map;
import java.util.UUID;
import vn.edu.examgrading.submission.dto.CompleteSubmissionResponse;
import vn.edu.examgrading.submission.dto.InitSubmissionRequest;
import vn.edu.examgrading.submission.dto.InitSubmissionResponse;
import vn.edu.examgrading.submission.dto.ReplaceFileRequest;
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

    /**
     * §4.4 public file access (EO only via gateway): presigned GET 5 phút.
     * ORIGINAL accepts {@code version} (default = current, superseded scans stay
     * viewable until retention); ANONYMIZED always serves the current version.
     */
    Map<String, Object> fileAccess(UUID submissionId, String type, Integer version);

    CompleteSubmissionResponse complete(UUID submissionId);

    /**
     * §4.4 replace-file step 1: validates eligibility (T24/T25 + Q-13), creates
     * a PENDING {@code file_replacement} and returns a presigned POST ticket.
     * Submission status is unchanged until {@code complete}.
     */
    Map<String, Object> replaceFile(UUID submissionId, ReplaceFileRequest request, String authorization);

    /**
     * §4.4 replace-file step 2: HEAD-checks the uploaded object, re-validates
     * eligibility, runs discard-open-work, then commits the new ORIGINAL
     * version + UPLOADED + VALIDATE job in one transaction and publishes it.
     * Idempotent on an already-COMPLETED replacement.
     */
    Map<String, Object> completeReplacement(UUID submissionId, UUID replacementId);

    /**
     * §4.4: cancel the submission's live PENDING replacement — marks it
     * CANCELLED and deletes the uploaded object if present. 404 when there is
     * no pending request.
     */
    void cancelReplacement(UUID submissionId);

    /**
     * §4.6/T27: EO reopens an anonymization issue on a READY_FOR_GRADING
     * submission — creates Review ANONYMIZATION/MASK_INSUFFICIENT OPEN via
     * Result, then transitions to REVIEW_REQUIRED. Returns
     * {@code {reviewId,status:"OPEN"}} (201). Idempotent replay returns the
     * same unclosed review.
     */
    Map<String, Object> createAnonymizationIssue(UUID submissionId, String reason,
            String authorization, String idempotencyKey);

    /**
     * §4.1 retry (T17/T18/T19): REVIEW_REQUIRED needs an ACTION_AUTHORIZED
     * review matching the step (FORCE_ANONYMIZE→VALIDATE+force, REPROCESS→
     * ANONYMIZE); FAILED retries only retry-exhausted codes with matching step.
     */
    Map<String, Object> retry(UUID submissionId, String step, Boolean force,
            UUID reviewId, UUID templateId, String authorization);
}
