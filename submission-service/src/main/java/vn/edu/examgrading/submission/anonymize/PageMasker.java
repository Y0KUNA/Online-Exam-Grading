package vn.edu.examgrading.submission.anonymize;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
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
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.CropRegion;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.CutRegion;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.Region;

/**
 * Builds the ANONYMIZED PDF per spec §7. Every scanned side is rasterized to an
 * RGB bitmap. Selected edge strips are removed from each output page.
 * Pages are cropped only from the explicit layout stored on the paper template.
 * This avoids guessing a centre line: real templates can have covers, margins,
 * unequal page widths, and different layouts on the two sides of a sheet.
 */
@Component
public class PageMasker {

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
        try (PDDocument src = PDDocument.load(new ByteArrayInputStream(pdfBytes));
             PDDocument out = new PDDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            validateRegions(regions);

            if (src.getNumberOfPages() < 1) {
                throw new LimitExceededException("PDF has no pages");
            }

            PDFRenderer renderer = new PDFRenderer(src);
            java.util.ArrayList<RenderedSide> renderedSides = new java.util.ArrayList<>();
            for (int i = 0; i < src.getNumberOfPages(); i++) {
                PDRectangle media = src.getPage(i).getMediaBox();
                checkPageLimits(media);
                int dpi = resolveDpi(media.getWidth(), media.getHeight());
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);

                renderedSides.add(new RenderedSide(image, dpi));
            }
            addLogicalPages(out, renderedSides, regions);

            out.save(bos);
            return bos.toByteArray();
        } catch (MaskException | LimitExceededException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("anonymization failed", e);
        }
    }

    private static void addLogicalPages(PDDocument out, List<RenderedSide> sides, MaskRegions regions)
            throws java.io.IOException, MaskException {
        List<CropRegion> crops = regions.cropRegions();
        if (crops == null || crops.isEmpty()) {
            for (int i = 0; i < sides.size(); i++) {
                RenderedSide side = sides.get(i);
                int templatePage = templatePageFor(i, regions);
                addImagePage(out, crop(side.image(), cropWithoutCuts(new Region(0, 0, 1, 1), templatePage, regions)), side.dpi());
            }
            return;
        }

        int templatePageCount = regions.templatePageCount();
        List<CropRegion> ordered = crops.stream()
            .sorted(java.util.Comparator.comparingInt(CropRegion::outputOrder)).toList();
        for (int sheetStart = 0; sheetStart < sides.size(); sheetStart += templatePageCount) {
            for (CropRegion crop : ordered) {
                int sourceIndex = sheetStart + crop.templatePage() - 1;
                if (sourceIndex >= sides.size()) {
                    continue;
                }
                RenderedSide side = sides.get(sourceIndex);
                addImagePage(out, crop(side.image(), cropWithoutCuts(crop.region(), crop.templatePage(), regions)), side.dpi());
            }
        }
    }

    private static int templatePageFor(int physicalPageIndex, MaskRegions regions) {
        return physicalPageIndex % regions.templatePageCount() + 1;
    }

    private static BufferedImage crop(BufferedImage source, int x, int y, int width, int height) {
        BufferedImage cropped = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = cropped.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, width, height, x, y, x + width, y + height, null);
        } finally {
            graphics.dispose();
        }
        return cropped;
    }

    private static BufferedImage crop(BufferedImage source, Region region) {
        int x = (int) Math.round(region.x() * source.getWidth());
        int y = (int) Math.round(region.y() * source.getHeight());
        int width = (int) Math.round(region.width() * source.getWidth());
        int height = (int) Math.round(region.height() * source.getHeight());
        return crop(source, x, y, width, height);
    }

    private static Region cropWithoutCuts(Region crop, int templatePage, MaskRegions regions)
            throws MaskException {
        Region result = crop;
        for (CutRegion cut : regions.cutRegions()) {
            if (cut.templatePage() == templatePage) result = subtractEdgeRegion(result, cut.region());
        }
        return result;
    }

    private static Region subtractEdgeRegion(Region crop, Region removal) throws MaskException {
        // A pointer-drawn region very rarely lands at exactly 0 or 1.  Treat a
        // one-percent gap as the page edge; otherwise a visually edge-aligned
        // detachable strip (for example right=0.991) fails at worker time.
        final double epsilon = 0.01;
        double cropRight = crop.x() + crop.width();
        double removalRight = removal.x() + removal.width();
        boolean intersects = removal.x() < cropRight - epsilon && removalRight > crop.x() + epsilon
            && removal.y() < crop.y() + crop.height() - epsilon
            && removal.y() + removal.height() > crop.y() + epsilon;
        if (!intersects) return crop;
        boolean spansHeight = removal.y() <= crop.y() + epsilon
            && removal.y() + removal.height() >= crop.y() + crop.height() - epsilon;
        if (!spansHeight) throw new MaskException("MASK_NOT_EDGE", "cut region must span the full page height");
        if (removal.x() <= crop.x() + epsilon) {
            double newX = Math.min(cropRight, removalRight);
            return new Region(newX, crop.y(), cropRight - newX, crop.height());
        }
        if (removalRight >= cropRight - epsilon) {
            double newRight = Math.max(crop.x(), removal.x());
            return new Region(crop.x(), crop.y(), newRight - crop.x(), crop.height());
        }
        throw new MaskException("MASK_NOT_EDGE", "cut region must touch a crop edge");
    }

    private static void addImagePage(PDDocument out, BufferedImage image, int dpi) throws java.io.IOException {
        PDImageXObject xImage = LosslessFactory.createFromImage(out, image);
        // PDFRenderer applies CropBox/rotation. Size the output from the bitmap
        // itself so a cropped page cannot be stretched back to the spread size.
        float outputWidth = (float) (image.getWidth() * 72.0 / dpi);
        float outputHeight = (float) (image.getHeight() * 72.0 / dpi);
        PDPage page = new PDPage(new PDRectangle(outputWidth, outputHeight));
        out.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
            cs.drawImage(xImage, 0, 0, outputWidth, outputHeight);
        }
    }

    private record RenderedSide(BufferedImage image, int dpi) {}


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

    private void validateRegions(MaskRegions regions) throws MaskException {
        if (regions == null || regions.cutRegions().isEmpty() || regions.templatePageCount() < 1) {
            throw new MaskException("CUT_REGION_MISSING", "template has no cut region");
        }
        for (CropRegion crop : regions.cropRegions()) {
            if (crop.templatePage() < 1 || crop.templatePage() > regions.templatePageCount()
                    || crop.outputOrder() < 1) {
                throw new MaskException("INVALID_CROP", "crop page and order must be positive");
            }
            Region region = crop.region();
            if (region == null || region.x() < 0 || region.y() < 0 || region.width() <= 0
                    || region.height() <= 0 || region.x() + region.width() > 1.0
                    || region.y() + region.height() > 1.0) {
                throw new MaskException("INVALID_CROP", "crop region out of bounds");
            }
        }
        for (CutRegion cut : regions.cutRegions()) {
            Region region = cut.region();
            if (cut.templatePage() < 1 || cut.templatePage() > regions.templatePageCount()
                    || region == null || region.x() < 0 || region.y() < 0 || region.width() <= 0
                    || region.height() <= 0 || region.x() + region.width() > 1.0
                    || region.y() + region.height() > 1.0) {
                throw new MaskException("INVALID_CUT", "cut region out of bounds");
            }
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
