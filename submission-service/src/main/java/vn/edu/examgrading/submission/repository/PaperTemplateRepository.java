package vn.edu.examgrading.submission.repository;

import java.util.Optional;
import java.util.UUID;

/** Template lookup contract used by the upload guard (impl in TICKET 03). */
public interface PaperTemplateRepository {

    boolean existsActive(UUID templateId);

    /** Get template dimensions for validation. Returns null if not found or inactive. */
    Optional<TemplateInfo> findTemplateInfo(UUID templateId);

    /** Get mask + pseudonym print regions stored in {@code mask jsonb} (§6.4). */
    Optional<MaskRegions> findMaskRegions(UUID templateId);

    record TemplateInfo(UUID templateId, double pageWidth, double pageHeight, boolean active, byte[] sourcePdf) {
        public TemplateInfo(UUID templateId, double pageWidth, double pageHeight, boolean active) {
            this(templateId, pageWidth, pageHeight, active, null);
        }
    }

    /** Normalized [0,1] region, origin top-left (§6.4). */
    record Region(double x, double y, double width, double height) {}

    record MaskRegions(Region mask, Region print, String fontName, double fontSize, String align) {}
}
