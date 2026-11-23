package vn.edu.examgrading.submission.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.examgrading.submission.service.AnonymizationPreviewService;
import vn.edu.examgrading.submission.service.AnonymizationPreviewService.PreviewRequest;
import vn.edu.examgrading.submission.service.AnonymizationPreviewService.PreviewResult;

/** §4.5 anonymization-preview — EO only (gateway enforces the role). */
@RestController
@RequestMapping("/api/v1")
public class AnonymizationPreviewController {

    private final AnonymizationPreviewService previewService;

    public AnonymizationPreviewController(AnonymizationPreviewService previewService) {
        this.previewService = previewService;
    }

    @PostMapping("/submissions/{submissionId}/anonymization-preview")
    public ResponseEntity<byte[]> preview(@PathVariable java.util.UUID submissionId,
            @RequestBody(required = false) PreviewRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        PreviewResult result = previewService.preview(submissionId, request, authorization);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setCacheControl(CacheControl.noStore());
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline");
        headers.add("X-Page-Width-Pt", String.valueOf(result.pageWidthPt()));
        headers.add("X-Page-Height-Pt", String.valueOf(result.pageHeightPt()));
        if (result.deviationPercent() != null) {
            headers.add("X-Template-Deviation-Percent", String.valueOf(result.deviationPercent()));
        }
        if (result.sizeCheckPass() != null) {
            headers.add("X-Size-Check", result.sizeCheckPass() ? "PASS" : "FAIL");
        }
        return ResponseEntity.ok().headers(headers).body(result.png());
    }
}
