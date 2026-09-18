package com.studentresume.portal.service;

import com.studentresume.portal.model.ResumeData;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.util.Set;

/**
 * Converts the selected Thymeleaf resume template to a PDF byte array using OpenHTMLtoPDF.
 *
 * <p>The same HTML/CSS templates rendered in the browser are reused for PDF output, so
 * the exported file matches what the student saw on screen.
 */
@Service
public class PdfExportService {

    /** Mirrors the same allowlist BuilderController.switchTemplate() already validates against. */
    private static final Set<String> KNOWN_TEMPLATES = Set.of("ats", "editorial", "sidebar");

    private final TemplateEngine templateEngine;

    public PdfExportService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /**
     * Renders {@code resumeData} using the template identified by
     * {@code resumeData.getSelectedTemplate()} and converts the result to PDF bytes.
     *
     * @param resumeData session bean with all resume content
     * @return PDF file as a byte array
     * @throws Exception if Thymeleaf rendering or PDF conversion fails
     */
    public byte[] export(ResumeData resumeData) throws Exception {
        String html = renderHtml(resumeData);
        return convertToPdf(html);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String renderHtml(ResumeData resumeData) {
        Context ctx = new Context();
        ctx.setVariable("resume", resumeData);

        // Falls back to "ats" for null/empty AND for anything unrecognized — guards against ever
        // trying to resolve a template file that doesn't exist (e.g. stale/corrupted data) and
        // throwing deep inside Thymeleaf instead of degrading to a safe default.
        String template = resumeData.getSelectedTemplate();
        if (template == null || !KNOWN_TEMPLATES.contains(template)) {
            template = "ats";
        }

        // Use PDF-specific templates for proper CSS rendering
        String templateName = "resume-templates/" + template + "-pdf";
        return templateEngine.process(templateName, ctx);
    }

    private byte[] convertToPdf(String html) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        }
    }
}
