package com.studentresume.portal.controller;

import com.studentresume.portal.model.ResumeData;
import com.studentresume.portal.service.AtsScoringService;
import com.studentresume.portal.service.CurrentUserProvider;
import com.studentresume.portal.service.PdfExportService;
import com.studentresume.portal.service.ResumePersistenceService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Review page and PDF export.
 */
@Controller
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final ResumeData       resumeData;
    private final PdfExportService pdfExportService;
    private final AtsScoringService atsScoringService;
    private final ResumePersistenceService resumePersistenceService;
    private final CurrentUserProvider currentUserProvider;

    public ExportController(ResumeData resumeData,
                            PdfExportService pdfExportService,
                            AtsScoringService atsScoringService,
                            ResumePersistenceService resumePersistenceService,
                            CurrentUserProvider currentUserProvider) {
        this.resumeData       = resumeData;
        this.pdfExportService = pdfExportService;
        this.atsScoringService = atsScoringService;
        this.resumePersistenceService = resumePersistenceService;
        this.currentUserProvider = currentUserProvider;
    }

    /** Full-width final preview in the selected template. */
    @GetMapping("/review")
    public String review(HttpSession session, Model model) {
        model.addAttribute("resume", resumeData);
        model.addAttribute("atsResult", atsScoringService.score(resumeData));
        model.addAttribute("user", currentUserProvider.require(session));
        return "review";
    }

    /**
     * Streams the PDF back as a download.
     * The session bean is unchanged; the student can loop back to the builder.
     * Auto-saves first, so the exported PDF always matches the latest saved state even if
     * the student never explicitly clicked Save.
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(HttpSession session) {
        try {
            resumePersistenceService.saveCurrentResume(resumeData, currentUserProvider.require(session));
            byte[] pdf = pdfExportService.export(resumeData);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "resume.pdf");
            headers.setContentLength(pdf.length);

            return ResponseEntity.ok().headers(headers).body(pdf);
        } catch (Exception ex) {
            // Previously swallowed silently — a failed export left zero trace in the logs,
            // making it undiagnosable in production.
            log.error("PDF export failed: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().build();
        }
    }
}
