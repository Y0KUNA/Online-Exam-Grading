package vn.edu.examgrading.examgrading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.examgrading.examgrading.dto.CreateExamRequest;
import vn.edu.examgrading.examgrading.repository.JdbcExamRepository;

class ExamServiceTest {
    @Test
    void createPersistsDraftAndReturnsItsProjection() {
        JdbcExamRepository repository = mock(JdbcExamRepository.class);
        when(repository.findById(any())).thenReturn(Map.of("status", "DRAFT"));
        ExamService service = new ExamService(repository);

        Map<String, Object> created = service.create(new CreateExamRequest("Thi cuối kỳ", "Toán", "2026-12-15T08:00", "K66", BigDecimal.TEN, true, 2, BigDecimal.ONE));

        verify(repository).insert(any(UUID.class), anyString(), anyString(), anyString(), any(), anyString(), any(), anyBoolean(), anyInt(), any());
        verify(repository).findById(any(UUID.class));
        assertThat(created).containsEntry("status", "DRAFT");
    }
}
