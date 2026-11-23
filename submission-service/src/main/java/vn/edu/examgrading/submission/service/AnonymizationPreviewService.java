package vn.edu.examgrading.submission.service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.submission.anonymize.PageMasker;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.MaskRegions;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository.Region;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;

/**
 * §4.5 anonymization-preview (EXAM_OFFICE only via gateway): renders page 1 of
 * an ORIGINAL file exactly as the ANONYMIZE worker would ({@link PageMasker}
 * MASKED) or with red/green region outlines (OUTLINE). Persists nothing —
 * only an {@code ANONYMIZATION_PREVIEWED} audit row. Bounded by a semaphore,
 * a hard timeout and a per-actor rate limit.
 */
@Service
public class AnonymizationPreviewService {

    /** Placeholder số phách for MASKED previews — never persisted, no Identity call. */
    private static final String PREVIEW_PSEUDONYM = "PHÁCH";

    private final SubmissionRepositoryMarker submissionRepo;
    private final ExamFileRepository examFileRepo;
    private final PaperTemplateRepository templateRepo;
    private final ObjectStorageGateway storage;
    private final PageMasker pageMasker;
    private final JdbcTemplate jdbc;
    private final int renderDpi;
    private final int minDpi;
    private final int maxDpi;
    private final int previewTimeoutSeconds;
    private final int rateLimitPerMinute;
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "anon-preview");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, ArrayDeque<Instant>> hits = new ConcurrentHashMap<>();

    public AnonymizationPreviewService(
            SubmissionRepositoryMarker submissionRepo,
            ExamFileRepository examFileRepo,
            PaperTemplateRepository templateRepo,
            ObjectStorageGateway storage,
            PageMasker pageMasker,
            JdbcTemplate jdbc,
            @Value("${app.pdf.render-dpi:200}") int renderDpi,
            @Value("${app.preview.min-dpi:50}") int minDpi,
            @Value("${app.preview.max-dpi:200}") int maxDpi,
            @Value("${app.preview.timeout-seconds:15}") int previewTimeoutSeconds,
            @Value("${app.preview.rate-limit-per-minute:10}") int rateLimitPerMinute,
            @Value("${app.preview.max-concurrency:2}") int maxConcurrency) {
        this.submissionRepo = submissionRepo;
        this.examFileRepo = examFileRepo;
        this.templateRepo = templateRepo;
        this.storage = storage;
        this.pageMasker = pageMasker;
        this.jdbc = jdbc;
        this.renderDpi = renderDpi;
        this.minDpi = minDpi;
        this.maxDpi = maxDpi;
        this.previewTimeoutSeconds = previewTimeoutSeconds;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.permits = new Semaphore(maxConcurrency);
    }

    public record PreviewRequest(Integer version, UUID templateId, String mode, Object dpi, Override override) {}
    public record Override(RegionBody mask, RegionBody print) {}
    public record RegionBody(Double x, Double y, Double width, Double height,
                             String fontName, Double fontSize, String align) {}

    public record PreviewResult(byte[] png, double pageWidthPt, double pageHeightPt,
                                Double deviationPercent, Boolean sizeCheckPass) {}

    public PreviewResult preview(UUID submissionId, PreviewRequest request, String authorization) {
        checkRateLimit(actorKey(authorization));
        if (!permits.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PREVIEW_BUSY");
        }
        try {
            PreviewResult result = CompletableFuture
                .supplyAsync(() -> doPreview(submissionId, request), executor)
                .get(previewTimeoutSeconds, TimeUnit.SECONDS);
            audit(submissionId, request);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "PREVIEW_TIMEOUT");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ResponseStatusException rse) throw rse;
            if (cause instanceof PageMasker.MaskException me) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, me.getTechnicalCode());
            }
            if (cause instanceof PageMasker.LimitExceededException) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_CORRUPT");
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_CORRUPT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "PREVIEW_TIMEOUT");
        } finally {
            permits.release();
        }
    }

    private PreviewResult doPreview(UUID submissionId, PreviewRequest request) {
        Submission submission = submissionRepo.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "INVALID_STATE"));
        if (submission.status() == SubmissionStatus.UPLOADING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "INVALID_STATE");
        }

        ExamFileRepository.OriginalFile original = request != null && request.version() != null
            ? examFileRepo.findByTypeAndVersion(submissionId, "ORIGINAL", request.version())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "INVALID_STATE"))
            : examFileRepo.findCurrentOriginal(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "INVALID_STATE"));

        byte[] pdfBytes = storage.get(original.objectKey());

        int dpi = resolveDpi(request == null ? null : request.dpi());
        UUID templateId = request != null && request.templateId() != null
            ? request.templateId() : submission.templateId();
        MaskRegions base = templateId == null ? null
            : templateRepo.findMaskRegions(templateId).orElse(null);
        MaskRegions regions = mergeRegions(base, request == null ? null : request.override());

        String mode = request != null && request.mode() != null
            ? request.mode().toUpperCase() : "MASKED";
        if (!"MASKED".equals(mode) && !"OUTLINE".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MODE");
        }

        byte[] png;
        double pageWidthPt;
        double pageHeightPt;
        try (PDDocument doc = PDDocument.load(new java.io.ByteArrayInputStream(pdfBytes))) {
            if (doc.getNumberOfPages() < 1) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_CORRUPT");
            }
            PDRectangle media = doc.getPage(0).getMediaBox();
            pageWidthPt = media.getWidth();
            pageHeightPt = media.getHeight();

            if ("MASKED".equals(mode)) {
                if (regions == null) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_MASK_ERROR");
                }
                byte[] maskedPdf;
                try {
                    maskedPdf = pageMasker.anonymize(pdfBytes, regions, PREVIEW_PSEUDONYM);
                } catch (PageMasker.MaskException e) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getTechnicalCode());
                } catch (PageMasker.LimitExceededException e) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_CORRUPT");
                }
                try (PDDocument masked = PDDocument.load(new java.io.ByteArrayInputStream(maskedPdf))) {
                    png = renderPng(masked, 0, dpi);
                }
            } else {
                png = renderOutline(doc, regions, dpi);
            }
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_CORRUPT");
        }

        Double deviation = null;
        Boolean sizePass = null;
        if (templateId != null) {
            var info = templateRepo.findTemplateInfo(templateId).orElse(null);
            if (info != null) {
                deviation = maxDeviation(pdfBytes, info);
                sizePass = deviation <= 5.0;
            }
        }
        return new PreviewResult(png, pageWidthPt, pageHeightPt, deviation, sizePass);
    }

    private int resolveDpi(Object dpi) {
        if (dpi == null) return 100;
        if (dpi instanceof Number n) {
            int v = n.intValue();
            if (v < minDpi || v > maxDpi) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DPI");
            }
            return v;
        }
        if ("ANON_RENDER_DPI".equalsIgnoreCase(dpi.toString())) return renderDpi;
        try {
            int v = Integer.parseInt(dpi.toString());
            if (v < minDpi || v > maxDpi) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DPI");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DPI");
        }
    }

    /** Merge template regions with the non-persisted override; §6.4 bounds apply to the result. */
    private MaskRegions mergeRegions(MaskRegions base, Override override) {
        if (override == null || (override.mask() == null && override.print() == null)) {
            return base;
        }
        Region mask = override.mask() != null ? toRegion(override.mask()) : base == null ? null : base.mask();
        Region print = override.print() != null ? toRegion(override.print()) : base == null ? null : base.print();
        String fontName = override.print() != null && override.print().fontName() != null
            ? override.print().fontName() : base == null ? null : base.fontName();
        Double fontSize = override.print() != null && override.print().fontSize() != null
            ? override.print().fontSize() : base == null ? null : base.fontSize();
        String align = override.print() != null && override.print().align() != null
            ? override.print().align() : base == null ? null : base.align();
        if (mask == null || print == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REGION");
        }
        MaskRegions merged = new MaskRegions(mask, print, fontName, fontSize, align);
        // Same §6.4 validation PageMasker applies — fail fast with 422.
        try {
            pageMasker.validateRegionsForPreview(merged);
        } catch (PageMasker.MaskException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REGION");
        }
        return merged;
    }

    private Region toRegion(RegionBody r) {
        if (r.x() == null || r.y() == null || r.width() == null || r.height() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REGION");
        }
        return new Region(r.x(), r.y(), r.width(), r.height());
    }

    private byte[] renderPng(PDDocument doc, int pageIndex, int dpi) throws java.io.IOException {
        BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private byte[] renderOutline(PDDocument doc, MaskRegions regions, int dpi) throws java.io.IOException {
        BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(0, dpi, ImageType.RGB);
        Graphics2D g = image.createGraphics();
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            if (regions != null) {
                g.setColor(new java.awt.Color(220, 30, 30));
                g.drawRect((int) Math.round(regions.mask().x() * w),
                    (int) Math.round(regions.mask().y() * h),
                    (int) Math.round(regions.mask().width() * w),
                    (int) Math.round(regions.mask().height() * h));
                g.setColor(new java.awt.Color(30, 160, 30));
                g.drawRect((int) Math.round(regions.print().x() * w),
                    (int) Math.round(regions.print().y() * h),
                    (int) Math.round(regions.print().width() * w),
                    (int) Math.round(regions.print().height() * h));
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Largest |actual − template| / template % across all pages (same rule as VALIDATE ±5%). */
    private Double maxDeviation(byte[] pdfBytes, PaperTemplateRepository.TemplateInfo info) {
        try (PDDocument doc = PDDocument.load(new java.io.ByteArrayInputStream(pdfBytes))) {
            double expectedW = info.pageWidth();
            double expectedH = info.pageHeight();
            if (info.sourcePdf() != null && info.sourcePdf().length > 0) {
                try (PDDocument source = PDDocument.load(new java.io.ByteArrayInputStream(info.sourcePdf()))) {
                    PDRectangle box = source.getPage(0).getMediaBox();
                    expectedW = box.getWidth();
                    expectedH = box.getHeight();
                }
            }
            double max = 0;
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDRectangle box = doc.getPage(i).getMediaBox();
                max = Math.max(max, Math.abs(box.getWidth() - expectedW) / expectedW * 100);
                max = Math.max(max, Math.abs(box.getHeight() - expectedH) / expectedH * 100);
            }
            return max;
        } catch (Exception e) {
            return null;
        }
    }

    private void checkRateLimit(String actor) {
        Instant now = Instant.now();
        ArrayDeque<Instant> window = hits.computeIfAbsent(actor, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minusSeconds(60))) {
                window.pollFirst();
            }
            if (window.size() >= rateLimitPerMinute) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
            }
            window.addLast(now);
        }
    }

    private String actorKey(String authorization) {
        if (authorization == null) return "anonymous";
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        String[] parts = token.split("\\.");
        if (parts.length < 2) return "anonymous";
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            int i = payload.indexOf("\"sub\"");
            if (i < 0) return "anonymous";
            int start = payload.indexOf('"', payload.indexOf(':', i) + 1) + 1;
            int end = payload.indexOf('"', start);
            return payload.substring(start, end);
        } catch (RuntimeException e) {
            return "anonymous";
        }
    }

    private void audit(UUID submissionId, PreviewRequest request) {
        try {
            jdbc.update(
                "insert into audit_log(id,actor_id,action,entity_type,entity_id,detail) values (?,null,'ANONYMIZATION_PREVIEWED','submission',?,?::jsonb)",
                UUID.randomUUID(), submissionId,
                "{\"version\":" + (request != null && request.version() != null ? request.version() : "null")
                    + ",\"mode\":\"" + (request != null && request.mode() != null ? request.mode() : "MASKED") + "\"}");
        } catch (RuntimeException ignored) { }
    }
}
