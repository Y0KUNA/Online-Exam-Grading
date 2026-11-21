package vn.edu.examgrading.examgrading.dto;

import java.util.List;
import java.util.UUID;

/** A batch of ready submissions distributed to the selected graders. */
public record BulkAssignmentRequest(List<UUID> submissionIds, List<UUID> graderIds) { }
