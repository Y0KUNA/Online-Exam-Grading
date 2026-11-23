package vn.edu.examgrading.submission.anonymize;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.MaskRegions;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.Region;

/**
 * Builds the ANONYMIZED PDF per spec §7: page 1 is rasterized to an RGB bitmap,
 * the mask region is filled pure white #FFFFFF, the số phách is drawn in black
 * inside the print region, and the page is re-embedded losslessly (Flate).
 * Remaining pages are copied without annotations; all metadata is dropped.
 */
@Component
public class PageMasker {

    private static final int MARGIN_PX = 2;

    private final int renderDpi;
    private final int minRenderDpi;
    private final long maxRenderPixels;
    private final double maxPagePoints;
    private final long jobTimeoutSeconds;
    private final ExecutorService executor;

    public PageMasker(
            @Value("${app.pdf.render-dpi:200}") int renderDpi,
            @Value("${app.pdf.min-render-dpi:100}") int minRenderDpi,
            @Value("${app.pdf.max-render-pixels:40000000}") long maxRenderPixels,
            @Value("${app.pdf.max-page-points:14400}") double maxPagePoints,
            @Value("${app.pdf.job-timeout-seconds:120}") long jobTimeoutSeconds) {
        this.renderDpi = renderDpi;
        this.minRenderDpi = minRenderDpi;
        this.maxRenderPixels = maxRenderPixels;
        this.maxPagePoints = maxPagePoints;
        this.jobTimeoutSeconds = jobTimeoutSeconds;
        // Daemon threads: a timed-out job may keep burning CPU in the background
        // (PDFBox ignores interrupts), but must never block JVM shutdown (§7.5).
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "page-masker");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * @return anonymized PDF bytes. Throws {@link MaskException} for template
     *         region problems (business error, no retry) and
     *         {@link LimitExceededException} for resource-limit violations —
     *         including {@code PDF_JOB_TIMEOUT_SECONDS} expiry (§7.5).
     */
    public byte[] anonymize(byte[] pdfBytes, MaskRegions regions, String pseudonym)
            throws MaskException, LimitExceededException {
        Future<byte[]> future = executor.submit(() -> doAnonymize(pdfBytes, regions, pseudonym));
        try {
            return future.get(jobTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LimitExceededException(
                "PDF_JOB_TIMEOUT_SECONDS exceeded (" + jobTimeoutSeconds + "s)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof MaskException me) {
                throw me;
            }
            if (cause instanceof LimitExceededException le) {
                throw le;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("anonymization failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LimitExceededException("anonymization interrupted");
        }
    }

    byte[] doAnonymize(byte[] pdfBytes, MaskRegions regions, String pseudonym)
            throws MaskException, LimitExceededException {
        if (pseudonym == null || pseudonym.isBlank()) {
            throw new MaskException("PSEUDONYM_MISSING", "pseudonym is required");
        }
        try (PDDocument src = PDDocument.load(new ByteArrayInputStream(pdfBytes));
             PDDocument out = new PDDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            validateRegions(regions);

            if (src.getNumberOfPages() < 1) {
                throw new LimitExceededException("PDF has no pages");
            }

            PDPage firstPage = src.getPage(0);
            PDRectangle media = firstPage.getMediaBox();
            checkPageLimits(media);

            int dpi = resolveDpi(media.getWidth(), media.getHeight());
            BufferedImage image = new PDFRenderer(src).renderImageWithDPI(0, dpi, ImageType.RGB);

            paintMaskedPage(image, regions, pseudonym, dpi);

            PDImageXObject xImage = LosslessFactory.createFromImage(out, image);
            // PDFRenderer applies the page CropBox/rotation.  Using MediaBox
            // again here can stretch the rasterized page (notably rotated scan
            // templates).  Build a normalized page from the rendered bitmap so
            // its physical aspect ratio exactly matches what was masked.
            float outputWidth = (float) (image.getWidth() * 72.0 / dpi);
            float outputHeight = (float) (image.getHeight() * 72.0 / dpi);
            PDPage masked = new PDPage(new PDRectangle(outputWidth, outputHeight));
            out.addPage(masked);
            try (PDPageContentStream cs = new PDPageContentStream(out, masked)) {
                cs.drawImage(xImage, 0, 0, outputWidth, outputHeight);
            }

            for (int i = 1; i < src.getNumberOfPages(); i++) {
                PDPage copied = out.importPage(src.getPage(i));
                copied.setAnnotations(new ArrayList<>());
            }

            out.save(bos);
            return bos.toByteArray();
        } catch (MaskException | LimitExceededException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("anonymization failed", e);
        }
    }

    private void paintMaskedPage(BufferedImage image, MaskRegions regions, String pseudonym, int dpi) {
        int w = image.getWidth();
        int h = image.getHeight();

        int maskX = (int) Math.round(regions.mask().x() * w);
        int maskY = (int) Math.round(regions.mask().y() * h);
        int maskW = (int) Math.round(regions.mask().width() * w);
        int maskH = (int) Math.round(regions.mask().height() * h);

        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(maskX, maskY, maskW, maskH);

            drawPseudonym(g, regions, pseudonym, w, h, dpi);
        } finally {
            g.dispose();
        }
    }

    private void drawPseudonym(Graphics2D g, MaskRegions regions, String pseudonym,
                               int imgW, int imgH, int dpi) {
        Region print = regions.print();
        int px = (int) Math.round(print.x() * imgW);
        int py = (int) Math.round(print.y() * imgH);
        int pw = (int) Math.round(print.width() * imgW);
        int ph = (int) Math.round(print.height() * imgH);

        // fontSize is in PDF points; scale to rendered pixels via dpi/72.
        int fontPx = Math.max(8, (int) Math.round(regions.fontSize() * dpi / 72.0));
        Font font = new Font("SansSerif",
            regions.fontName() != null && regions.fontName().toLowerCase().contains("bold")
                ? Font.BOLD : Font.PLAIN, fontPx);
        g.setFont(font);
        g.setColor(Color.BLACK);

        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(pseudonym);
        int textH = fm.getAscent();
        int tx;
        String align = regions.align() == null ? "CENTER" : regions.align().toUpperCase();
        switch (align) {
            case "LEFT" -> tx = px + MARGIN_PX;
            case "RIGHT" -> tx = px + pw - textW - MARGIN_PX;
            default -> tx = px + (pw - textW) / 2;
        }
        int ty = py + (ph + textH) / 2 - fm.getDescent();
        g.drawString(pseudonym, tx, ty);
    }

    private int resolveDpi(double pageWidthPt, double pageHeightPt) throws LimitExceededException {
        double pixelsAtDpi = pageWidthPt * pageHeightPt * renderDpi * renderDpi / (72.0 * 72.0);
        if (pixelsAtDpi <= maxRenderPixels) {
            return renderDpi;
        }
        // Reduce DPI until the bitmap fits; never below minRenderDpi.
        double fitDpi = 72.0 * Math.sqrt(maxRenderPixels / (pageWidthPt * pageHeightPt));
        int dpi = (int) Math.min(renderDpi, Math.max(minRenderDpi, Math.floor(fitDpi)));
        double pixels = pageWidthPt * pageHeightPt * dpi * dpi / (72.0 * 72.0);
        if (pixels > maxRenderPixels) {
            throw new LimitExceededException("render pixels exceed limit at min dpi");
        }
        return dpi;
    }

    private void checkPageLimits(PDRectangle media) throws LimitExceededException {
        if (media.getWidth() > maxPagePoints || media.getHeight() > maxPagePoints
                || media.getWidth() <= 0 || media.getHeight() <= 0) {
            throw new LimitExceededException(
                "page size exceeds limit: " + media.getWidth() + "x" + media.getHeight());
        }
    }

    /** §6.4 region validation exposed for anonymization-preview overrides (same rules, no side effects). */
    public void validateRegionsForPreview(MaskRegions regions) throws MaskException {
        validateRegions(regions);
    }

    private void validateRegions(MaskRegions regions) throws MaskException {
        if (regions == null || regions.mask() == null || regions.print() == null) {
            throw new MaskException("MASK_REGION_MISSING", "template has no mask/print region");
        }
        for (Region r : new Region[]{regions.mask(), regions.print()}) {
            if (r.x() < 0 || r.y() < 0 || r.width() <= 0 || r.height() <= 0
                    || r.x() + r.width() > 1.0 || r.y() + r.height() > 1.0) {
                throw new MaskException("INVALID_REGION", "region out of bounds: " + r);
            }
        }
        Region m = regions.mask();
        Region p = regions.print();
        if (p.x() < m.x() || p.y() < m.y()
                || p.x() + p.width() > m.x() + m.width()
                || p.y() + p.height() > m.y() + m.height()) {
            throw new MaskException("INVALID_REGION", "print region must be inside mask region");
        }
    }

    /** Business/template error — no retry, creates REVIEW_REQUIRED. */
    public static class MaskException extends Exception {
        private final String technicalCode;

        public MaskException(String technicalCode, String message) {
            super(message);
            this.technicalCode = technicalCode;
        }

        public String getTechnicalCode() {
            return technicalCode;
        }
    }

    /** Resource-limit violation — FAILED with PDF_CORRUPT/LIMIT_EXCEEDED, no retry (§7.5). */
    public static class LimitExceededException extends Exception {
        public LimitExceededException(String message) {
            super(message);
        }
    }
}
