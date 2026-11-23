package vn.edu.examgrading.examgrading.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Scores entered by a grader for a single grading attempt. */
public record SaveScoresRequest(List<QuestionScore> scores) {
    public record QuestionScore(UUID questionId, BigDecimal score, String note, Boolean needsReview) { }
}
