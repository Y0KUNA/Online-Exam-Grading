package vn.edu.examgrading.submission.dto;

import java.util.List;
import java.util.Map;

/**
 * Cursor-paginated projection for {@code GET /api/v1/exams/{examId}/submissions}
 * (spec D-06): {@code {items:[...], nextCursor:string|null}}. Items use the same
 * public submission projection as {@code GET /api/v1/submissions/{id}}.
 */
public record SubmissionPageResponse(List<Map<String, Object>> items, String nextCursor) { }
