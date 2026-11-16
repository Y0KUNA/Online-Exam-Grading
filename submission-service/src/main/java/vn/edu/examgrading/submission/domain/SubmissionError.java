package vn.edu.examgrading.submission.domain;

/** Stable machine-readable codes returned in {@code ApiError.code} (TICKET 00 contract). */
public final class SubmissionError {

    private SubmissionError() { }

    public static final String UNSUPPORTED_CONTENT_TYPE = "UNSUPPORTED_CONTENT_TYPE";
    public static final String INVALID_FILE_SIZE = "INVALID_FILE_SIZE";
    public static final String TEMPLATE_NOT_FOUND = "TEMPLATE_NOT_FOUND";
    public static final String CANDIDATE_ALREADY_BOUND = "CANDIDATE_ALREADY_BOUND";
    public static final String INVALID_STATE = "INVALID_STATE";
    public static final String UPLOAD_EXPIRED = "UPLOAD_EXPIRED";
    public static final String UPLOAD_NOT_FOUND = "UPLOAD_NOT_FOUND";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
}
