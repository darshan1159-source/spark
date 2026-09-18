package com.studentresume.portal.model;

import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.util.ArrayList;
import java.util.List;

/**
 * The single source of truth for all resume data in a user session.
 * Scoped to the HTTP session — this is the in-memory working copy being edited; explicit
 * Save (or export) persists it to the {@code resumes} table via ResumePersistenceService.
 *
 * <p><b>Known limitation:</b> there is only one working copy per session. Editing two
 * different saved resumes in two browser tabs at once will cross-contaminate them — the
 * second tab's "select" silently rewrites this same bean out from under the first tab.
 * Accepted trade-off rather than giving each tab its own scope (a real architectural
 * addition) for a single-user tool; {@code DashboardController} auto-saves before switching
 * to limit the common case.
 *
 * <p>One specific consequence of sharing a bean IS mitigated, though: education/project/
 * experience entries are addressed by a stable id (see {@link Education#getId()} etc.), not
 * list position — so if one tab inserts or removes an entry, another tab's next edit (still
 * referencing an entry by the id it last rendered) can no longer land on the wrong entry just
 * because positions shifted. It can still go missing (removeIf no-ops on an id that's gone) or
 * get overwritten if both tabs edit the exact same entry, but it will never silently corrupt a
 * *different* entry than the one the student was actually looking at.
 *
 * <p>Two different DEVICES (separate sessions, hence separate ResumeData instances) editing the
 * same saved resume is a different problem — resolved at the database layer instead, via
 * optimistic locking (see {@code Resume.version}), not here.
 */
@Data
@Component
@SessionScope
public class ResumeData {

    /** DB row this working copy is bound to, or null if unsaved/new. Never client-suppliable. */
    private Long id;

    /**
     * The {@code Resume.version} this session last loaded or saved — what makes optimistic
     * locking actually detect a stale save (see {@code ResumePersistenceService.saveCurrentResume}).
     * Deliberately NOT refetched from the DB right before saving: the whole point is comparing
     * "what this session last knew" against "what's actually there now" — refetching first
     * would always match itself and could never detect a conflict.
     */
    private Long version;

    // Personal info
    private String fullName   = "";
    private String email      = "";
    private String phone      = "";
    private String linkedinUrl = "";
    private String githubUrl  = "";

    // Resume sections
    private List<Education>  education  = new ArrayList<>();
    private List<Project>    projects   = new ArrayList<>();
    private List<Experience> experience = new ArrayList<>();
    private List<String>     skills     = new ArrayList<>();

    /** Active layout: "ats" | "editorial" | "sidebar" */
    private String selectedTemplate = "ats";

    // -------------------------------------------------------------------------
    // Convenience mutators used by controllers (htmx partial posts)
    // -------------------------------------------------------------------------

    public void addEducation(Education e)   { education.add(e); }
    public void addProject(Project p)       { projects.add(p); }
    public void addExperience(Experience e) { experience.add(e); }

    /**
     * Removed by stable entry id, not list position — a stale reference (e.g. from a second
     * browser tab that hasn't seen a just-inserted/removed entry shift positions around) simply
     * finds nothing and no-ops, rather than removing whatever now happens to sit at that index.
     */
    public void removeEducation(String id)  { education.removeIf(e -> e.getId().equals(id)); }
    public void removeProject(String id)    { projects.removeIf(p -> p.getId().equals(id)); }
    public void removeExperience(String id) { experience.removeIf(e -> e.getId().equals(id)); }

    /**
     * Resets all fields to empty defaults — used when starting a blank resume.
     *
     * <p>Clears {@code id} too, deliberately folded in here rather than left as a second
     * statement at every call site — a call site that reset the fields but forgot to null
     * the id would cause the next save to silently overwrite the *previous* resume's row.
     */
    public void reset() {
        id          = null;
        version     = null;
        fullName    = "";
        email       = "";
        phone       = "";
        linkedinUrl = "";
        githubUrl   = "";
        education   = new ArrayList<>();
        projects    = new ArrayList<>();
        experience  = new ArrayList<>();
        skills      = new ArrayList<>();
        selectedTemplate = "ats";
    }
}
