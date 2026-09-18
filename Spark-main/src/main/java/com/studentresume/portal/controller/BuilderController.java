package com.studentresume.portal.controller;

import com.studentresume.portal.model.Education;
import com.studentresume.portal.model.Experience;
import com.studentresume.portal.model.Project;
import com.studentresume.portal.model.ResumeData;
import com.studentresume.portal.service.AtsScoringService;
import com.studentresume.portal.service.CurrentUserProvider;
import com.studentresume.portal.service.ResumePersistenceService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the two-pane builder page.
 *
 * <p>Every mutating action is an htmx partial post — the controller updates the
 * session bean and returns a Thymeleaf fragment that htmx swaps into the DOM.
 * The full-page {@code GET /builder} is only called on initial load.
 */
@Controller
@RequestMapping("/builder")
public class BuilderController {

    private final ResumeData       resumeData;
    private final AtsScoringService atsScoringService;
    private final ResumePersistenceService resumePersistenceService;
    private final CurrentUserProvider currentUserProvider;

    public BuilderController(ResumeData resumeData, AtsScoringService atsScoringService,
                             ResumePersistenceService resumePersistenceService,
                             CurrentUserProvider currentUserProvider) {
        this.resumeData       = resumeData;
        this.atsScoringService = atsScoringService;
        this.resumePersistenceService = resumePersistenceService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Explicit save point — persists the current in-memory working copy to its DB row.
     *
     * <p>On a version conflict (this resume was saved from another device since this session
     * last loaded it — see {@code Resume.version}), responds 409 instead of throwing a generic
     * 500. The in-memory session copy is left exactly as it was — nothing here silently
     * overwrites the other device's save, and nothing here discards this tab's unsaved edits
     * either; the student just needs to know a save didn't go through. The existing autosave JS
     * in builder.html already checks {@code res.ok} and falls back to showing "unsaved" on any
     * non-2xx status — 409 specifically gets a clearer message there.
     */
    @PostMapping("/save")
    public String save(HttpSession session, Model model, HttpServletResponse response) {
        try {
            resumePersistenceService.saveCurrentResume(resumeData, currentUserProvider.require(session));
        } catch (ObjectOptimisticLockingFailureException ex) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
        }
        return previewAndScore(model);
    }

    // =========================================================================
    // Full page
    // =========================================================================

    @GetMapping
    public String builder(HttpSession session, Model model) {
        model.addAttribute("resume", resumeData);
        model.addAttribute("atsResult", atsScoringService.score(resumeData));
        model.addAttribute("user", currentUserProvider.require(session));
        return "builder";
    }

    // =========================================================================
    // Personal info
    // =========================================================================

    @PostMapping("/personal")
    public String updatePersonal(
            @RequestParam(defaultValue = "") String fullName,
            @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "") String phone,
            @RequestParam(defaultValue = "") String linkedinUrl,
            @RequestParam(defaultValue = "") String githubUrl,
            Model model) {

        resumeData.setFullName(fullName);
        resumeData.setEmail(email);
        resumeData.setPhone(phone);
        resumeData.setLinkedinUrl(linkedinUrl);
        resumeData.setGithubUrl(githubUrl);

        return previewAndScore(model);
    }

    // =========================================================================
    // Template switcher
    // =========================================================================

    @PostMapping("/template")
    public String switchTemplate(@RequestParam String template, Model model) {
        if (List.of("ats", "editorial", "sidebar").contains(template)) {
            resumeData.setSelectedTemplate(template);
        }
        return previewAndScore(model);
    }

    // =========================================================================
    // Education
    // =========================================================================

    @PostMapping("/education/add")
    public String addEducation(Model model) {
        resumeData.addEducation(new Education());
        model.addAttribute("resume", resumeData);
        model.addAttribute("atsResult", atsScoringService.score(resumeData));
        return "fragments/education :: educationListWithPreview";
    }

    @PostMapping("/education/{id}")
    public String updateEducation(
            @PathVariable String id,
            @RequestParam(defaultValue = "") String institution,
            @RequestParam(defaultValue = "") String degree,
            @RequestParam(defaultValue = "") String dates,
            @RequestParam(defaultValue = "") String description,
            Model model) {

        resumeData.getEducation().stream()
                .filter(e -> e.getId().equals(id))
                .findFirst()
                .ifPresent(e -> {
                    e.setInstitution(institution);
                    e.setDegree(degree);
                    e.setDates(dates);
                    e.setDescription(description);
                });
        return previewAndScore(model);
    }

    @DeleteMapping("/education/{id}")
    public String deleteEducation(@PathVariable String id, Model model) {
        resumeData.removeEducation(id);
        model.addAttribute("resume", resumeData);
        model.addAttribute("atsResult", atsScoringService.score(resumeData));
        return "fragments/education :: educationListWithPreview";
    }

    // =========================================================================
    // Projects
    // =========================================================================

    @PostMapping("/projects/add")
    public String addProject(Model model) {
        resumeData.addProject(new Project());
        model.addAttribute("resume", resumeData);
        model.addAttribute("atsResult", atsScoringService.score(resumeData));
        return "fragments/projects :: projectListWithPreview";
    }

    @PostMapping("/projects/{id}")
    public String updateProject(
            @PathVariable String id,
            @RequestParam(defaultValue = "") String title,
            @RequestParam(defaultValue = "") String techStack,
            @RequestParam(defaultValue = "") String summary,
            Model model) {

        resumeData.getProjects().stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .ifPresent(p -> {
                    p.setTitle(title);
                    p.setTechStack(techStack);
                    p.setSummary(summary);
                });
        return previewAndScore(model);
    }

    @DeleteMapping("/projects/{id}")
    public String deleteProject(@PathVariable String id, Model model) {
        resumeData.removeProject(id);
        model.addAttribute("resume", resumeData);
        model.addAttribute("atsResult", atsScoringService.score(resumeData));
        return "fragments/projects :: projectListWithPreview";
    }

    // =========================================================================
    // Experience
    // =========================================================================

    @PostMapping("/experience/add")
    public String addExperience(Model model) {
        resumeData.addExperience(new Experience());
        model.addAttribute("resume", resumeData);
        model.addAttribute("atsResult", atsScoringService.score(resumeData));
        return "fragments/experience :: experienceListWithPreview";
    }

    @PostMapping("/experience/{id}")
    public String updateExperience(
            @PathVariable String id,
            @RequestParam(defaultValue = "") String title,
            @RequestParam(defaultValue = "") String company,
            @RequestParam(defaultValue = "") String dates,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "") String type,
            Model model) {

        resumeData.getExperience().stream()
                .filter(e -> e.getId().equals(id))
                .findFirst()
                .ifPresent(e -> {
                    e.setTitle(title);
                    e.setCompany(company);
                    e.setDates(dates);
                    e.setDescription(description);
                    e.setType(type);
                });
        return previewAndScore(model);
    }

    @DeleteMapping("/experience/{id}")
    public String deleteExperience(@PathVariable String id, Model model) {
        resumeData.removeExperience(id);
        model.addAttribute("resume", resumeData);
        model.addAttribute("atsResult", atsScoringService.score(resumeData));
        return "fragments/experience :: experienceListWithPreview";
    }

    // =========================================================================
    // Skills
    // =========================================================================

    /** Accepts comma-separated skills string, splits and stores as a List<String>. */
    @PostMapping("/skills")
    public String updateSkills(@RequestParam(defaultValue = "") String skillsInput, Model model) {
        List<String> skills = new ArrayList<>();
        for (String s : skillsInput.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) skills.add(trimmed);
        }
        resumeData.setSkills(skills);
        return previewAndScore(model);
    }

    // =========================================================================
    // Shared: return both the preview and the ATS badge in one swap
    // =========================================================================

    @GetMapping("/preview")
    public String preview(Model model) {
        return previewAndScore(model);
    }

    private String previewAndScore(Model model) {
        model.addAttribute("resume", resumeData);
        model.addAttribute("atsResult", atsScoringService.score(resumeData));
        return "fragments/preview :: previewAndBadge";
    }
}
