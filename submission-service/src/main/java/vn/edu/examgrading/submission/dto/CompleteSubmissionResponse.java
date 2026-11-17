package vn.edu.examgrading.submission.dto;

/** Response shape for the complete step; declared as a contract in TICKET 00, produced in TICKET 06. */
public record CompleteSubmissionResponse(String submissionId, String status) { }
