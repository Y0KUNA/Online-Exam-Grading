package vn.edu.examgrading.resultreview.dto;
import java.math.BigDecimal;
public record CouncilDecisionRequest(String decisionType, BigDecimal finalScore, String reason) { }
