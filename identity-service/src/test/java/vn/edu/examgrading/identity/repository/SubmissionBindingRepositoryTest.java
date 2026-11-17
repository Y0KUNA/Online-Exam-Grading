package vn.edu.examgrading.identity.repository;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for {@link SubmissionBindingRepository} (TICKET 01).
 * Uses H2 in-memory database for fast unit testing.
 */
class SubmissionBindingRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private SubmissionBindingRepository repository;
    private final UUID examId = UUID.randomUUID();
    private final UUID candidateId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        var dataSource = new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("schema.sql")
            .build();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new SubmissionBindingRepository(jdbcTemplate);
    }

    @Test
    void insertBinding_andFindActiveBinding() {
        UUID submissionId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();

        repository.insertBinding(bindingId, examId, submissionId, candidateId);

        Optional<SubmissionBindingRepository.BindingInfo> found = repository.findActiveBinding(submissionId);

        assertTrue(found.isPresent());
        assertEquals(bindingId, found.get().bindingId());
        assertEquals("ACTIVE", found.get().status());
    }

    @Test
    void findActiveBinding_returnsEmptyWhenNotExists() {
        UUID submissionId = UUID.randomUUID();

        Optional<SubmissionBindingRepository.BindingInfo> found = repository.findActiveBinding(submissionId);

        assertFalse(found.isPresent());
    }

    @Test
    void findActiveBinding_returnsEmptyWhenReleased() {
        UUID submissionId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();

        repository.insertBinding(bindingId, examId, submissionId, candidateId);
        repository.releaseBinding(submissionId, "UPLOAD_ABANDONED");

        Optional<SubmissionBindingRepository.BindingInfo> found = repository.findActiveBinding(submissionId);

        assertFalse(found.isPresent());
    }

    @Test
    void releaseBinding_marksAsReleased() {
        UUID submissionId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();

        repository.insertBinding(bindingId, examId, submissionId, candidateId);
        repository.releaseBinding(submissionId, "FILE_TOO_LARGE");

        Optional<SubmissionBindingRepository.BindingInfo> found = repository.findActiveBinding(submissionId);
        assertFalse(found.isPresent());
    }

    @Test
    void releaseBinding_isIdempotent() {
        UUID submissionId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();

        repository.insertBinding(bindingId, examId, submissionId, candidateId);
        repository.releaseBinding(submissionId, "UPLOAD_ABANDONED");

        assertDoesNotThrow(() -> repository.releaseBinding(submissionId, "UPLOAD_ABANDONED"));
    }

    @Test
    void releaseBinding_noOpWhenBindingNotExists() {
        UUID submissionId = UUID.randomUUID();

        assertDoesNotThrow(() -> repository.releaseBinding(submissionId, "UPLOAD_ABANDONED"));
    }

    @Test
    void getCandidateId_returnsCandidateIdWhenActive() {
        UUID submissionId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();

        repository.insertBinding(bindingId, examId, submissionId, candidateId);

        Optional<UUID> found = repository.getCandidateId(submissionId);

        assertTrue(found.isPresent());
        assertEquals(candidateId, found.get());
    }

    @Test
    void getCandidateId_returnsEmptyWhenNotExists() {
        UUID submissionId = UUID.randomUUID();

        Optional<UUID> found = repository.getCandidateId(submissionId);

        assertFalse(found.isPresent());
    }

    @Test
    void getCandidateId_returnsEmptyWhenReleased() {
        UUID submissionId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();

        repository.insertBinding(bindingId, examId, submissionId, candidateId);
        repository.releaseBinding(submissionId, "UPLOAD_ABANDONED");

        Optional<UUID> found = repository.getCandidateId(submissionId);

        assertFalse(found.isPresent());
    }
}
