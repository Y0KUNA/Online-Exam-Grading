package vn.edu.examgrading.submission.domain;

/**
 * The single source of truth for submission state transitions (TICKET 00).
 * Extracted from the internal status endpoint so the API service and the signed
 * internal transitions share one machine instead of duplicating the rules.
 * Pure domain: no DB, no HTTP, no framework types.
 */
public final class SubmissionStatusMachine {

    private SubmissionStatusMachine() { }

    public static boolean canTransition(SubmissionStatus from, SubmissionStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return switch (from) {
            case UPLOADING -> to == SubmissionStatus.UPLOADED || to == SubmissionStatus.FAILED;
            case UPLOADED -> to == SubmissionStatus.VALIDATING;
            case VALIDATING -> to == SubmissionStatus.ANONYMIZING
                    || to == SubmissionStatus.REVIEW_REQUIRED
                    || to == SubmissionStatus.FAILED;
            case ANONYMIZING -> to == SubmissionStatus.READY_FOR_GRADING
                    || to == SubmissionStatus.FAILED;
            case READY_FOR_GRADING -> to == SubmissionStatus.GRADING
                    || to == SubmissionStatus.REVIEW_REQUIRED;
            case GRADING -> to == SubmissionStatus.COMPLETED
                    || to == SubmissionStatus.REVIEW_REQUIRED;
            case REVIEW_REQUIRED -> to == SubmissionStatus.GRADING
                    || to == SubmissionStatus.VALIDATING
                    || to == SubmissionStatus.ANONYMIZING
                    || to == SubmissionStatus.COMPLETED
                    || to == SubmissionStatus.FINALIZED;
            case COMPLETED -> to == SubmissionStatus.FINALIZED
                    || to == SubmissionStatus.REVIEW_REQUIRED;
            case FINALIZED -> to == SubmissionStatus.REVIEW_REQUIRED;
            case FAILED -> to == SubmissionStatus.VALIDATING
                    || to == SubmissionStatus.ANONYMIZING;
        };
    }

    /** Returns {@code to} when the move is legal; otherwise throws {@link IllegalArgumentException}. */
    public static SubmissionStatus transition(SubmissionStatus from, SubmissionStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalArgumentException("ILLEGAL_TRANSITION: " + from + " -> " + to);
        }
        return to;
    }
}
