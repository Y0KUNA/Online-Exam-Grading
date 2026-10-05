package vn.edu.examgrading.examgrading.dto;
import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
public record CreateExamRequest(String name, String subject, Instant examTime, BigDecimal scale, boolean crossGradingEnabled, int graderCount, BigDecimal differenceThreshold, UUID templateId) { }
