package vn.edu.examgrading.submission.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.examgrading.submission.anonymize.PageMasker;
import vn.edu.examgrading.submission.domain.Submission;
import vn.edu.examgrading.submission.domain.SubmissionStatus;
import vn.edu.examgrading.submission.repository.ExamFileRepository;
import vn.edu.examgrading.submission.repository.PaperTemplateRepository;
import vn.edu.examgrading.submission.repository.SubmissionRepositoryMarker;
import vn.edu.examgrading.submission.service.AnonymizationPreviewService.Override;
import vn.edu.examgrading.submission.service.AnonymizationPreviewService.PreviewRequest;
import vn.edu.examgrading.submission.service.AnonymizationPreviewService.PreviewResult;
import vn.edu.examgrading.submission.service.AnonymizationPreviewService.RegionBody;
import vn.edu.examgrading.submission.storage.ObjectStorageGateway;

@ExtendWith(MockitoExtension.class)
class AnonymizationPreviewServiceTest {

    @Mock private SubmissionRepositoryMarker submissionRepo;
    @Mock private ExamFileRepository examFileRepo;
    @Mock private PaperTemplateRepository templateRepo;
    @Mock private ObjectStorageGateway storage;
    @Mock private JdbcTemplate jdbc;

    private AnonymizationPreviewService service;
    private final UUID submissionId = UUID.randomUUID();
    private final UUID examId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private byte[] pdf;

    @BeforeEach
    void setUp() throws Exception {
        // Small dpi keeps rendering fast in unit tests.
        PageMasker masker = new PageMasker(72, 50, 40_000_000L, 14400, 30);
        service = new AnonymizationPreviewService(submissionRepo, examFileRepo, templateRepo,
            storage, masker, jdbc, 72, 50, 200, 15, 10, 2);
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(new PDRectangle(595, 842)));
            doc.save(out);
            pdf = out.toByteArray();
        }
        Submission submission = new Submission(submissionId, examId, templateId,
            SubmissionStatus.UPLOADED, 1, 0, null, (long) pdf.length, null, null, "key",
            null, 0, Instant.now());
        lenient().when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(submission));
        lenient().when(examFileRepo.findCurrentOriginal(submissionId))
            .thenReturn(Optional.of(new ExamFileRepository.OriginalFile(
                UUID.randomUUID(), 1, (long) pdf.length, "orig/v1.pdf")));
        lenient().when(storage.get("orig/v1.pdf")).thenReturn(pdf);
        lenient().when(templateRepo.findMaskRegions(templateId)).thenReturn(Optional.of(
            new PaperTemplateRepository.MaskRegions(
                new PaperTemplateRepository.Region(0.1, 0.1, 0.5, 0.3),
                new PaperTemplateRepository.Region(0.15, 0.15, 0.2, 0.1),
                "Arial", 12.0, "center")));
    }

    private PreviewRequest req(String mode, Object dpi, Override override) {
        return new PreviewRequest(null, null, mode, dpi, override);
    }

    @Test
    void maskedPreview_returnsPngAndAudits() {
        PreviewResult res = service.preview(submissionId, req("MASKED", null, null), null);
        assertEquals(595, res.pageWidthPt(), 0.01);
        assertEquals(842, res.pageHeightPt(), 0.01);
        // PNG magic bytes
        assertEquals((byte) 0x89, res.png()[0]);
        assertEquals('P', res.png()[1]);
        verify(jdbc).update(contains("ANONYMIZATION_PREVIEWED"), any(), eq(submissionId), any());
    }

    @Test
    void outlinePreview_drawsRegionBoxes() {
        PreviewResult res = service.preview(submissionId, req("OUTLINE", 100, null), null);
        assertEquals((byte) 0x89, res.png()[0]);
    }

    @Test
    void overrideOutsideUnitInterval_422InvalidRegion() {
        Override bad = new Override(
            new RegionBody(1.5, 0.1, 0.2, 0.2, null, null, null), null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.preview(submissionId, req("MASKED", null, bad), null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("INVALID_REGION", ex.getReason());
    }

    @Test
    void overridePrintOutsideMask_422() {
        Override bad = new Override(null,
            new RegionBody(0.9, 0.9, 0.05, 0.05, null, null, null));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.preview(submissionId, req("MASKED", null, bad), null));
        assertEquals("INVALID_REGION", ex.getReason());
    }

    @Test
    void validOverride_mergesWithTemplatePrint() {
        Override ok = new Override(
            new RegionBody(0.05, 0.05, 0.6, 0.4, null, null, null), null);
        PreviewResult res = service.preview(submissionId, req("MASKED", null, ok), null);
        assertEquals((byte) 0x89, res.png()[0]);
    }

    @Test
    void dpiValidation() {
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
            () -> service.preview(submissionId, req("MASKED", 10, null), null)).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
            () -> service.preview(submissionId, req("MASKED", "bogus", null), null)).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
            () -> service.preview(submissionId, req("MASKED", "ANON_RENDER_DPI_EXTRA", null), null)).getStatusCode());
    }

    @Test
    void anonRenderDpiResolvesToRenderDpi() {
        PreviewResult res = service.preview(submissionId, req("MASKED", "ANON_RENDER_DPI", null), null);
        assertEquals((byte) 0x89, res.png()[0]);
    }

    @Test
    void missingOriginalAndUploading_409() {
        when(examFileRepo.findCurrentOriginal(submissionId)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.preview(submissionId, req("MASKED", null, null), null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());

        Submission uploading = new Submission(submissionId, examId, templateId,
            SubmissionStatus.UPLOADING, 0, 0, Instant.now().plusSeconds(600), null, null, null,
            "key", null, 0, Instant.now());
        when(submissionRepo.findById(submissionId)).thenReturn(Optional.of(uploading));
        ex = assertThrows(ResponseStatusException.class,
            () -> service.preview(submissionId, req("MASKED", null, null), null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void badPdf_422() {
        when(storage.get("orig/v1.pdf")).thenReturn("not-a-pdf".getBytes());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.preview(submissionId, req("MASKED", null, null), null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
    }

    @Test
    void rateLimit_thirdCall429() {
        service.preview(submissionId, req("MASKED", null, null), null);
        service.preview(submissionId, req("MASKED", null, null), null);
        // rateLimitPerMinute=10 in setUp — use a fresh service with limit 2
        PageMasker masker = new PageMasker(72, 50, 40_000_000L, 14400, 30);
        AnonymizationPreviewService limited = new AnonymizationPreviewService(
            submissionRepo, examFileRepo, templateRepo, storage, masker, jdbc,
            72, 50, 200, 15, 2, 2);
        limited.preview(submissionId, req("MASKED", null, null), null);
        limited.preview(submissionId, req("MASKED", null, null), null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> limited.preview(submissionId, req("MASKED", null, null), null));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }
}
