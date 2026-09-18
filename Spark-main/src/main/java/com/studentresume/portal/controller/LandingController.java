package com.studentresume.portal.controller;

import com.studentresume.portal.model.ResumeData;
import com.studentresume.portal.service.CurrentUserProvider;
import com.studentresume.portal.service.ResumePersistenceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Handles the landing page and the choice screen (start blank vs. upload).
 */
@Controller
public class LandingController {

    private final ResumeData resumeData;
    private final ResumePersistenceService resumePersistenceService;
    private final CurrentUserProvider currentUserProvider;

    public LandingController(ResumeData resumeData,
                              ResumePersistenceService resumePersistenceService,
                              CurrentUserProvider currentUserProvider) {
        this.resumeData = resumeData;
        this.resumePersistenceService = resumePersistenceService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Landing page — animated hero, how-it-works, template previews. Public, no login required.
     * Nav/CTAs adapt if a session is already logged in (link to the dashboard instead of register).
     */
    @GetMapping("/")
    public String landing(HttpSession session, Model model) {
        boolean loggedIn = session != null && session.getAttribute("userId") != null;
        model.addAttribute("loggedIn", loggedIn);
        return "landing";
    }

    /** Choice screen — "Start from scratch" vs "Upload your resume". Requires login. */
    @GetMapping("/start")
    public String start(HttpSession session, Model model) {
        model.addAttribute("user", currentUserProvider.require(session));
        return "choice";
    }

    /**
     * Blank path — reset the session bean and send the student straight to the builder.
     * No data is lost if the session was already populated because reset() clears everything.
     * Immediately saves a DB row too, so the new resume shows up on the dashboard right away
     * rather than only after an explicit Save.
     */
    @PostMapping("/start/blank")
    public String startBlank(HttpSession session, RedirectAttributes ra) {
        resumeData.reset();
        resumePersistenceService.saveCurrentResume(resumeData, currentUserProvider.require(session));
        return "redirect:/builder";
    }
}
