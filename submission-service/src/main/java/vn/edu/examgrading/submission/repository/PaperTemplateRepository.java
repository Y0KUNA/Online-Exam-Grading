package vn.edu.examgrading.submission.repository;

import java.util.UUID;

/** Template lookup contract used by the upload guard (impl in TICKET 03). */
public interface PaperTemplateRepository {

    boolean existsActive(UUID templateId);
}
