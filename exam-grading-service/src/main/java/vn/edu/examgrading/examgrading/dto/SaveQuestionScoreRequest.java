package vn.edu.examgrading.examgrading.dto;

import java.math.BigDecimal;

/** A single question score, compatible with the public grading contract. */
public record SaveQuestionScoreRequest(BigDecimal score, String note, Boolean needsReview) { }
