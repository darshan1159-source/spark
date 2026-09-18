package com.studentresume.portal.controller;

import com.studentresume.portal.model.ParseStatus;
import com.studentresume.portal.model.Resume;
import com.studentresume.portal.model.ResumeData;
import com.studentresume.portal.model.User;
import com.studentresume.portal.service.CurrentUserProvider;
import com.studentresume.portal.service.ResumePersistenceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.stream.Collectors;

/**
 * "My Resumes" — list, create, select-to-edit, and delete a logged-in user's saved resumes.
 */
@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    private final ResumeData resumeData;
    private final ResumePersistenceService resumePersistenceService;
    private final CurrentUserProvider currentUserProvider;

    public DashboardController(ResumeData resumeData,
                                ResumePersistenceService resumePersistenceService,
                                CurrentUserProvider currentUserProvider) {
        this.resumeData = resumeData;
        this.resumePersistenceService = resumePersistenceService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public String dashboard(HttpSession session, Model model) {
        User owner = currentUserProvider.require(session);
        model.addAttribute("resumes", resumePersistenceService.listForUser(owner));
        model.addAttribute("user", owner);

        // Catches resumes that finished parsing while the student wasn't watching the pending
        // card live (closed the tab, came back later) — same one-time-notification guarantee as
        // the live-polling path, driven by the same persisted notifiedReady flag. Drives both the
        // page-level summary banner below AND the per-card "New" tag (dashboard.html looks up
        // each resume's id in justReadyIds) — same claim, two places it shows up.
        List<Resume> justReady = resumePersistenceService.claimReadyNotifications(owner);
        model.addAttribute("justReadyIds", justReady.stream().map(Resume::getId).collect(Collectors.toSet()));
        if (!justReady.isEmpty()) {
            String summary = justReady.size() == 1
                    ? "Your resume has been parsed and is ready to edit."
                    : justReady.size() + " resumes finished processing and are ready to edit.";
            model.addAttribute("readySummary", summary);
        }
        return "dashboard";
    }

    /**
     * Auto-saves whatever's currently open (if it has an id) before abandoning it for a new
     * one, then hands off to the existing /start choice flow. Does NOT call reset() itself —
     * whichever path the student picks next (/start/blank or /upload) already does.
     *
     * <p>On a version conflict (another device saved this same resume more recently — see
     * {@code Resume.version}), the auto-save is skipped rather than overwriting that newer save,
     * and the student is told so — but they still proceed to /start, since blocking "start a new
     * resume" over an unrelated old resume's conflict wouldn't help anyone.
     */
    @PostMapping("/new")
    public String newResume(HttpSession session, RedirectAttributes ra) {
        if (resumeData.getId() != null) {
            try {
                resumePersistenceService.saveCurrentResume(resumeData, currentUserProvider.require(session));
            } catch (ObjectOptimisticLockingFailureException ex) {
                ra.addFlashAttribute("error",
                        "Your last changes to the previous resume couldn't be saved — it was edited elsewhere.");
            }
        }
        return "redirect:/start";
    }

    /**
     * Auto-saves the currently-open resume first if switching away from it — the mitigation
     * for the documented multi-tab/switch data-loss risk (see ResumeData's javadoc) — then
     * loads the selected resume (ownership-checked) into the session. Refuses a resume that
     * isn't READY yet (still PENDING, or FAILED) — there's nothing real to load into the builder.
     *
     * <p>A version conflict on that auto-save (see {@code Resume.version}) doesn't block
     * switching — it just means the previous resume's last edits from this session are lost
     * rather than silently overwriting whatever the other device saved; the newly selected
     * resume still loads normally.
     */
    @PostMapping("/{id}/select")
    public String select(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        User owner = currentUserProvider.require(session);

        Resume target = resumePersistenceService.getForUser(id, owner);
        if (target.getParseStatus() != ParseStatus.READY) {
            ra.addFlashAttribute("error", "This resume is still being processed.");
            return "redirect:/dashboard";
        }

        if (resumeData.getId() != null && !resumeData.getId().equals(id)) {
            try {
                resumePersistenceService.saveCurrentResume(resumeData, owner);
            } catch (ObjectOptimisticLockingFailureException ex) {
                ra.addFlashAttribute("error",
                        "Your last changes to the previous resume couldn't be saved — it was edited elsewhere.");
            }
        }
        resumePersistenceService.loadIntoSession(id, owner, resumeData);
        return "redirect:/builder";
    }

    /**
     * Polling target for a PENDING dashboard card (see {@code fragments/resume-card.html}'s
     * {@code hx-trigger="every 4s"}) — returns that one resume's current card, self-terminating
     * once the card is no longer PENDING (the returned markup simply stops carrying the poll
     * trigger). Claiming the "ready" notification is deliberately a separate step from reading
     * the resume: two tabs polling the same resume must both keep seeing its current state
     * regardless of which one wins the notification claim.
     */
    @GetMapping("/{id}/status")
    public String status(@PathVariable Long id, HttpSession session, Model model) {
        User owner = currentUserProvider.require(session);
        Resume resume = resumePersistenceService.getForUser(id, owner);
        boolean justBecameReady = resumePersistenceService.claimReadyNotification(id, owner);

        model.addAttribute("resume", resume);
        model.addAttribute("justBecameReady", justBecameReady);
        return "fragments/resume-card :: resumeCardStatus";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, HttpSession session) {
        User owner = currentUserProvider.require(session);
        resumePersistenceService.delete(id, owner);
        if (id.equals(resumeData.getId())) {
            resumeData.reset();
        }
        return "redirect:/dashboard";
    }
}
