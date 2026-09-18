package com.studentresume.portal.controller;

import com.studentresume.portal.model.Resume;
import com.studentresume.portal.model.User;
import com.studentresume.portal.service.CurrentUserProvider;
import com.studentresume.portal.service.CvParsingService;
import com.studentresume.portal.service.CvParsingService.GateOutcome;
import com.studentresume.portal.service.CvParsingService.GateResult;
import com.studentresume.portal.service.ResumeParsingOrchestrator;
import com.studentresume.portal.service.ResumePersistenceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

/**
 * Handles CV file upload: a fast synchronous check, then hands the expensive parsing off to a
 * background thread and redirects immediately — see {@link CvParsingService#runQuickGate} /
 * {@link ResumeParsingOrchestrator}. The student watches the new resume as a "processing" card
 * on the dashboard rather than waiting on this request; it updates itself live via polling.
 *
 * <p>Never touches the session-scoped {@code ResumeData} bean — the whole flow is DB-only, which
 * incidentally means two uploads (two tabs, or back-to-back) no longer race on shared state the
 * way editing a resume in two tabs still can (see {@code ResumeData}'s own doc comment).
 */
@Controller
public class UploadController {

    private final CvParsingService cvParsingService;
    private final ResumePersistenceService resumePersistenceService;
    private final ResumeParsingOrchestrator resumeParsingOrchestrator;
    private final CurrentUserProvider currentUserProvider;

    public UploadController(CvParsingService cvParsingService,
                            ResumePersistenceService resumePersistenceService,
                            ResumeParsingOrchestrator resumeParsingOrchestrator,
                            CurrentUserProvider currentUserProvider) {
        this.cvParsingService = cvParsingService;
        this.resumePersistenceService = resumePersistenceService;
        this.resumeParsingOrchestrator = resumeParsingOrchestrator;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         HttpSession session, RedirectAttributes ra) throws IOException {
        if (file.isEmpty()) {
            // choice.html only renders a flash attribute named "uploadError" (see the banner
            // near its top) — an "error" attribute here would be silently dropped, never shown.
            ra.addFlashAttribute("uploadError", "No file selected. Please choose a PDF or DOCX.");
            return "redirect:/start";
        }

        // Eagerly read the bytes: MultipartFile's backing stream/temp file is only valid for the
        // life of this request, which the background parsing phase deliberately outlives.
        byte[] fileBytes = file.getBytes();
        GateResult gate = cvParsingService.runQuickGate(fileBytes, file.getOriginalFilename());

        if (gate.outcome() == GateOutcome.NOT_A_RESUME) {
            ra.addFlashAttribute("uploadError", "That doesn't look like a resume/CV. Please upload an " +
                    "actual resume, and make sure it's a real PDF or DOCX file (not a scanned " +
                    "image or screenshot) so we can read the text.");
            return "redirect:/start";
        }
        if (gate.outcome() == GateOutcome.TECHNICAL_FAILURE) {
            ra.addFlashAttribute("uploadError", "We couldn't read that file. Please make sure it's " +
                    "a valid PDF or DOCX and try again.");
            return "redirect:/start";
        }

        User owner = currentUserProvider.require(session);
        Resume pending = resumePersistenceService.createPending(owner);
        resumeParsingOrchestrator.processResumeAsync(gate.rawText(), pending.getId(), owner.getId());

        return "redirect:/dashboard";
    }
}
