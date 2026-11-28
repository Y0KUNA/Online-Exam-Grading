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
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.CutRegion;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.Region;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §7.4 mandatory test: decode the image embedded on page 1 of the ANONYMIZED
 * PDF and verify that a selected edge strip is physically removed. Page 1
 * must contain exactly one image XObject and no annotations.
 */
class PageMaskerTest {

    private static final int PAGE_W = 300;
    private static final int PAGE_H = 300;

    private final PageMasker masker = new PageMasker(200, 100, 40_000_000L, 14400, 120);

    private static final Region MASK = new Region(0, 0, 0.20, 1);
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
    void selectedEdgeStripIsRemoved() throws Exception {
        byte[] out = masker.anonymize(sourcePdf(), REGIONS, "PH-001");
        DecodedPage decoded = decodeFirstPageImage(out);
        assertEquals(0.8 * PAGE_W, decoded.image().getWidth() * 72.0 / 200.0, 1.0,
            "the output bitmap must be narrower after removing the left strip");
    }

    @Test
    void pseudonymIsNotRendered() throws Exception {
        byte[] out = masker.anonymize(blankPdf(), REGIONS, "PH-001");
        BufferedImage img = decodeFirstPageImage(out).image();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                assertEquals(0xFFFFFF, rgb, "no pseudonym text may be rendered");
            }
        }
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
        // Pointer input commonly lands slightly inside the rendered PDF edge.
        // 0.991 must still be treated as the right detachable edge.
        Region detachableStrip = new Region(0.9, 0, 0.091, 1);
        MaskRegions layout = new MaskRegions(detachableStrip,
            new Region(0.91, 0.1, 0.08, 0.1), "Helvetica-Bold", 14.0, "CENTER", 1, 2,
            List.of(
                new CropRegion(1, 4, new Region(0, 0, 0.5, 1)),
                new CropRegion(1, 1, new Region(0.5, 0, 0.5, 1)),
                new CropRegion(2, 2, new Region(0, 0, 0.5, 1)),
                new CropRegion(2, 3, new Region(0.5, 0, 0.5, 1))),
            List.of(new CutRegion(1, new Region(0.49, 0, 0.02, 1)),
                new CutRegion(1, detachableStrip)),
            List.of());
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
    void cutThatDoesNotTouchEdge_isRejected() {
        MaskRegions bad = new MaskRegions(null, null, null, 0, null, 1, 1, List.of(),
            List.of(new CutRegion(1, new Region(0.2, 0, 0.2, 1))), List.of());
        assertThrows(PageMasker.MaskException.class,
            () -> masker.anonymize(sourcePdfQuietly(), bad, "PH-001"));
    }

    @Test
    void blankPseudonymIsAcceptedBecauseItIsNotRendered() throws Exception {
        assertNotNull(masker.anonymize(sourcePdfQuietly(), REGIONS, "  "));
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

    private static byte[] blankPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(new PDRectangle(PAGE_W, PAGE_H)));
            doc.save(out);
            return out.toByteArray();
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
