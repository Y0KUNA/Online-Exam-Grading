package vn.edu.examgrading.examgrading.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Payload received when the examination office creates an exam draft. */
public record CreateExamRequest(
        String name,
        String subject,
        String examTime,
        String gradeLevel,
        BigDecimal scale,
        Boolean crossGradingEnabled,
        Integer graderCount,
        BigDecimal differenceThreshold,
        UUID templateId) { }
