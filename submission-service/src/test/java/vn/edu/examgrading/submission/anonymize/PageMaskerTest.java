package vn.edu.examgrading.submission.anonymize;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.MaskRegions;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.CropRegion;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.Region;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §7.4 mandatory test: decode the image embedded on page 1 of the ANONYMIZED
 * PDF and assert every pixel inside the mask region — outside the print
 * region (2px margin) — is exactly #FFFFFF. Page 1 must contain exactly one
 * image XObject and no annotations.
 */
class PageMaskerTest {

    private static final int PAGE_W = 300;
    private static final int PAGE_H = 300;

    private final PageMasker masker = new PageMasker(200, 100, 40_000_000L, 14400, 120);

    private static final Region MASK = new Region(0.10, 0.10, 0.50, 0.30);
    private static final Region PRINT = new Region(0.15, 0.15, 0.30, 0.10);
    private static final MaskRegions REGIONS = new MaskRegions(
        MASK, PRINT, "Helvetica-Bold", 14.0, "CENTER");

    private static byte[] sourcePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page1 = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
            doc.addPage(page1);
            // PII-like content inside the mask area that must be erased.
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 20);
                cs.newLineAtOffset(40, 200);
                cs.showText("Nguyen Van A - SBD 001");
                cs.endText();
            }
            PDPage page2 = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(40, 200);
                cs.showText("page 2 content");
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private record DecodedPage(BufferedImage image, int xObjectCount) {}

    private static DecodedPage decodeFirstPageImage(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
            PDPage page = doc.getPage(0);
            List<PDImageXObject> images = new ArrayList<>();
            int xObjectCount = 0;
            if (page.getResources() != null) {
                for (COSName name : page.getResources().getXObjectNames()) {
                    PDXObject xo = page.getResources().getXObject(name);
                    xObjectCount++;
                    if (xo instanceof PDImageXObject img) {
                        images.add(img);
                    }
                }
            }
            assertEquals(1, images.size(), "page 1 must embed exactly one image XObject");
            assertTrue(page.getAnnotations() == null || page.getAnnotations().isEmpty(),
                "page 1 must have no annotations");
            return new DecodedPage(images.get(0).getImage(), xObjectCount);
        }
    }

    @Test
    void maskPixelsArePureWhiteOutsidePrintRegion() throws Exception {
        byte[] out = masker.anonymize(sourcePdf(), REGIONS, "PH-001");
        DecodedPage decoded = decodeFirstPageImage(out);
        BufferedImage img = decoded.image();
        int w = img.getWidth();
        int h = img.getHeight();

        int maskX = (int) Math.round(MASK.x() * w);
        int maskY = (int) Math.round(MASK.y() * h);
        int maskW = (int) Math.round(MASK.width() * w);
        int maskH = (int) Math.round(MASK.height() * h);
        int printX = (int) Math.round(PRINT.x() * w);
        int printY = (int) Math.round(PRINT.y() * h);
        int printW = (int) Math.round(PRINT.width() * w);
        int printH = (int) Math.round(PRINT.height() * h);
        int margin = 2;

        int checked = 0;
        for (int y = maskY; y < maskY + maskH; y++) {
            for (int x = maskX; x < maskX + maskW; x++) {
                boolean inPrint = x >= printX - margin && x < printX + printW + margin
                    && y >= printY - margin && y < printY + printH + margin;
                if (inPrint) {
                    continue;
                }
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                assertEquals(0xFFFFFF, rgb,
                    "pixel (" + x + "," + y + ") inside mask must be #FFFFFF");
                checked++;
            }
        }
        assertTrue(checked > 1000, "sanity: mask area too small, checked=" + checked);
    }

    @Test
    void pseudonymIsDrawnInsidePrintRegion() throws Exception {
        byte[] out = masker.anonymize(sourcePdf(), REGIONS, "PH-001");
        BufferedImage img = decodeFirstPageImage(out).image();
        int w = img.getWidth();
        int h = img.getHeight();
        int printX = (int) Math.round(PRINT.x() * w);
        int printY = (int) Math.round(PRINT.y() * h);
        int printW = (int) Math.round(PRINT.width() * w);
        int printH = (int) Math.round(PRINT.height() * h);

        boolean hasDarkPixel = false;
        for (int y = printY; y < printY + printH && !hasDarkPixel; y++) {
            for (int x = printX; x < printX + printW; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < 128 && g < 128 && b < 128) {
                    hasDarkPixel = true;
                    break;
                }
            }
        }
        assertTrue(hasDarkPixel, "pseudonym text must be drawn (dark pixels) inside print region");
    }

    @Test
    void remainingPagesAreCopiedWithoutAnnotations() throws Exception {
        byte[] out = masker.anonymize(sourcePdf(), REGIONS, "PH-001");
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(out))) {
            assertEquals(2, doc.getNumberOfPages());
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                assertTrue(page.getAnnotations() == null || page.getAnnotations().isEmpty(),
                    "page " + (i + 1) + " must have no annotations");
            }
            // No document metadata must survive anonymization.
            assertNull(doc.getDocumentInformation().getTitle());
        }
    }

    @Test
    void landscapeSheetSidesAreSplitInReadingOrder() throws Exception {
        Region detachableStrip = new Region(0.9, 0, 0.1, 1);
        MaskRegions layout = new MaskRegions(detachableStrip,
            new Region(0.91, 0.1, 0.08, 0.1), "Helvetica-Bold", 14.0, "CENTER", 1,
            List.of(
                new CropRegion(1, 4, new Region(0, 0, 0.5, 1)),
                new CropRegion(1, 1, new Region(0.5, 0, 0.5, 1)),
                new CropRegion(2, 2, new Region(0, 0, 0.5, 1)),
                new CropRegion(2, 3, new Region(0.5, 0, 0.5, 1))));
        byte[] out = masker.anonymize(twoLandscapeSides(), layout, "PH-001");

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(out))) {
            // Two scanned sides (two physical sheets in this fixture), each with
            // left and right logical pages, become four grader-facing pages.
            assertEquals(4, doc.getNumberOfPages());
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDRectangle box = doc.getPage(i).getMediaBox();
                assertTrue(box.getHeight() > box.getWidth(),
                    "split output page " + (i + 1) + " must be portrait");
                assertTrue(doc.getPage(i).getAnnotations() == null
                    || doc.getPage(i).getAnnotations().isEmpty());
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            assertPageColour(renderer, 0, Color.BLUE);   // front-right: page 1
            assertPageColour(renderer, 1, Color.GREEN);  // back-left: page 2
            assertPageColour(renderer, 2, Color.YELLOW); // back-right: page 3
            assertPageColour(renderer, 3, Color.RED);    // front-left: page 4
            assertTrue(doc.getPage(0).getMediaBox().getWidth() < doc.getPage(1).getMediaBox().getWidth(),
                "the detachable identity strip must be removed from page 1, not painted white");
        }
    }

    @Test
    void printRegionOutsideMask_isRejected() {
        MaskRegions bad = new MaskRegions(MASK,
            new Region(0.8, 0.8, 0.2, 0.2), "Helvetica", 12, "CENTER");
        assertThrows(PageMasker.MaskException.class,
            () -> masker.anonymize(sourcePdfQuietly(), bad, "PH-001"));
    }

    @Test
    void blankPseudonym_isRejected() {
        assertThrows(PageMasker.MaskException.class,
            () -> masker.anonymize(sourcePdfQuietly(), REGIONS, "  "));
    }

    @Test
    void exceedingJobTimeout_throwsLimitExceeded() {
        // §7.5: PDF_JOB_TIMEOUT_SECONDS — a hung render must surface as
        // LimitExceededException (FAILED/PDF_CORRUPT, no retry) instead of
        // blocking the consumer forever.
        PageMasker slow = new PageMasker(200, 100, 40_000_000L, 14400, 1) {
            @Override
            byte[] doAnonymize(byte[] pdfBytes, MaskRegions regions, String pseudonym) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new byte[0];
            }
        };
        assertThrows(PageMasker.LimitExceededException.class,
            () -> slow.anonymize(sourcePdfQuietly(), REGIONS, "PH-001"));
    }

    private static byte[] sourcePdfQuietly() {
        try {
            return sourcePdf();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] twoLandscapeSides() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < 2; i++) {
                PDPage spread = new PDPage(new PDRectangle(1200, 842));
                doc.addPage(spread);
                try (PDPageContentStream cs = new PDPageContentStream(doc, spread)) {
                    cs.setNonStrokingColor(i == 0 ? Color.RED : Color.GREEN);
                    cs.addRect(0, 0, 600, 842);
                    cs.fill();
                    cs.setNonStrokingColor(i == 0 ? Color.BLUE : Color.YELLOW);
                    cs.addRect(600, 0, 600, 842);
                    cs.fill();
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 12);
                    cs.newLineAtOffset(40, 200);
                    cs.showText("left logical page " + (i + 1));
                    cs.newLineAtOffset(320, 200);
                    cs.showText("right logical page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void assertPageColour(PDFRenderer renderer, int pageIndex, Color expected)
            throws Exception {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, 72, ImageType.RGB);
        assertEquals(expected.getRGB() & 0xFFFFFF, image.getRGB(10, 10) & 0xFFFFFF,
            "unexpected logical page at output index " + pageIndex);
    }
}
