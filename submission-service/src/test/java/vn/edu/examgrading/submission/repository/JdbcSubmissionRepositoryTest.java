package vn.edu.examgrading.submission.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link JdbcSubmissionRepository} (TICKET 03).
 * Runs against real PostgreSQL at localhost:5433 (no Testcontainers per TICKET requirements).
 *
 * NOTE: Temporarily disabled due to environment setup issues.
 * To enable: ensure PostgreSQL is running at localhost:5433 with credentials exam_grading/change-me
 */
@Disabled("Requires manual PostgreSQL setup - see TICKET 03 completion status")
class JdbcSubmissionRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcSubmissionRepository repository;
    private final UUID examId = UUID.randomUUID();
    private final UUID candidateId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();

    private void setupConnection() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://localhost:5433/submission_db");
        ds.setUsername("exam_grading");
        ds.setPassword("change-me");
        jdbcTemplate = new JdbcTemplate(ds);
        repository = new JdbcSubmissionRepository(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM submission_event WHERE submission_id IN (SELECT submission_id FROM submission WHERE exam_id = ?)", examId);
        jdbcTemplate.update("DELETE FROM submission WHERE exam_id = ?", examId);
    }

    @Test
    void insertUploading_andFindById() {
        setupConnection();
        UUID submissionId = UUID.randomUUID();
        Instant uploadExpiresAt = Instant.now().plusSeconds(600);
        long declaredBytes = 52428800L;
        String objectKey = examId + "/" + submissionId + "/v1.pdf";

        repository.insertUploading(submissionId, examId, candidateId, templateId,
            uploadExpiresAt, declaredBytes, objectKey);

        Optional<Submission> found = repository.findById(submissionId);

        assertTrue(found.isPresent());
        Submission submission = found.get();
        assertEquals(submissionId, submission.submissionId());
        assertEquals(examId, submission.examId());
        assertEquals(candidateId, submission.candidateId());
        assertEquals(templateId, submission.templateId());
        assertEquals(SubmissionStatus.UPLOADING, submission.status());
        assertEquals(1, submission.currentFileVersion());
        assertEquals(0, submission.progress());
        assertEquals(declaredBytes, submission.declaredFileSizeBytes());
        assertEquals(objectKey, submission.objectKey());
        assertNull(submission.failureCode());
    }

    @Test
    void insertUploading_duplicateCandidateExam_throwsDuplicateKeyException() {
        setupConnection();
        UUID submissionId1 = UUID.randomUUID();
        UUID submissionId2 = UUID.randomUUID();
        Instant uploadExpiresAt = Instant.now().plusSeconds(600);

        repository.insertUploading(submissionId1, examId, candidateId, templateId,
            uploadExpiresAt, 52428800L, "key1.pdf");

        assertThrows(DuplicateKeyException.class, () -> {
            repository.insertUploading(submissionId2, examId, candidateId, templateId,
                uploadExpiresAt, 52428800L, "key2.pdf");
        });
    }

    @Test
    void findByExam_returnsSubmissionsForExam() {
        setupConnection();
        UUID submissionId1 = UUID.randomUUID();
        UUID submissionId2 = UUID.randomUUID();
        UUID candidateId2 = UUID.randomUUID();
        Instant uploadExpiresAt = Instant.now().plusSeconds(600);

        repository.insertUploading(submissionId1, examId, candidateId, templateId,
            uploadExpiresAt, 52428800L, "key1.pdf");
        repository.insertUploading(submissionId2, examId, candidateId2, templateId,
            uploadExpiresAt, 52428800L, "key2.pdf");

        List<Submission> submissions = repository.findByExam(examId);

        assertEquals(2, submissions.size());
        assertTrue(submissions.stream().anyMatch(s -> s.submissionId().equals(submissionId1)));
        assertTrue(submissions.stream().anyMatch(s -> s.submissionId().equals(submissionId2)));
    }

    @Test
    void transition_updatesStatusWhenExpectedMatches() {
        setupConnection();
        UUID submissionId = UUID.randomUUID();
        Instant uploadExpiresAt = Instant.now().plusSeconds(600);

        repository.insertUploading(submissionId, examId, candidateId, templateId,
            uploadExpiresAt, 52428800L, "key.pdf");

        int affected = repository.transition(submissionId, SubmissionStatus.UPLOADING,
            SubmissionStatus.UPLOADED, 100);

        assertEquals(1, affected);

        Optional<Submission> updated = repository.findById(submissionId);
        assertTrue(updated.isPresent());
        assertEquals(SubmissionStatus.UPLOADED, updated.get().status());
        assertEquals(100, updated.get().progress());
    }

    @Test
    void transition_returnsZeroWhenExpectedDoesNotMatch() {
        setupConnection();
        UUID submissionId = UUID.randomUUID();
        Instant uploadExpiresAt = Instant.now().plusSeconds(600);

        repository.insertUploading(submissionId, examId, candidateId, templateId,
            uploadExpiresAt, 52428800L, "key.pdf");

        int affected = repository.transition(submissionId, SubmissionStatus.UPLOADED,
            SubmissionStatus.GRADING, 50);

        assertEquals(0, affected);

        Optional<Submission> unchanged = repository.findById(submissionId);
        assertTrue(unchanged.isPresent());
        assertEquals(SubmissionStatus.UPLOADING, unchanged.get().status());
    }

    @Test
    void findExpiredUploading_returnsOnlyExpiredRows() {
        setupConnection();
        UUID submissionId1 = UUID.randomUUID();
        UUID submissionId2 = UUID.randomUUID();
        UUID candidateId2 = UUID.randomUUID();
        Instant expiredTime = Instant.now().minusSeconds(60);
        Instant futureTime = Instant.now().plusSeconds(600);

        repository.insertUploading(submissionId1, examId, candidateId, templateId,
            expiredTime, 52428800L, "key1.pdf");
        repository.insertUploading(submissionId2, examId, candidateId2, templateId,
            futureTime, 52428800L, "key2.pdf");

        List<Submission> expired = repository.findExpiredUploading(Instant.now());

        assertEquals(1, expired.size());
        assertEquals(submissionId1, expired.get(0).submissionId());
    }
}
