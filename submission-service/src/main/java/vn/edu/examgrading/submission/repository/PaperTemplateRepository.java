package vn.edu.examgrading.submission.repository;

import java.util.Optional;
import java.util.List;
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

    /** A logical output page cropped from one page of the scanned template. */
    record CropRegion(int templatePage, int outputOrder, Region region) {}
    /** A physical phách strip removed from a template page before output. */
    record CutRegion(int templatePage, Region region) {}
    record RedactionRegion(int templatePage, Region region) {}

    record MaskRegions(Region mask, Region print, String fontName, double fontSize, String align,
                       int maskPage, int templatePageCount, List<CropRegion> cropRegions,
                       List<CutRegion> cutRegions, List<RedactionRegion> redactionRegions) {
        /** Compatibility constructor for templates created before crop layouts existed. */
        public MaskRegions(Region mask, Region print, String fontName, double fontSize, String align) {
            this(mask, print, fontName, fontSize, align, 1, 1, List.of(), List.of(new CutRegion(1, mask)), List.of());
        }

        public MaskRegions(Region mask, Region print, String fontName, double fontSize, String align,
                           int maskPage, List<CropRegion> cropRegions) {
            this(mask, print, fontName, fontSize, align, maskPage,
                cropRegions.stream().mapToInt(CropRegion::templatePage).max().orElse(1), cropRegions,
                List.of(new CutRegion(maskPage, mask)), List.of());
        }
    }
}
