package vn.edu.examgrading.submission.dto;

import java.util.UUID;

public record InitSubmissionRequest(UUID candidateId, UUID templateId, String fileName, long fileSizeBytes, String contentType) { }
