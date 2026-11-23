package vn.edu.examgrading.submission.dto;

/**
 * {@code POST /api/v1/submissions/{id}/replace-file} body (§4.4):
 * {@code reason} is mandatory; {@code contentType} must be application/pdf.
 */
public record ReplaceFileRequest(
        String reason,
        String fileName,
        Long fileSizeBytes,
        String contentType) { }
