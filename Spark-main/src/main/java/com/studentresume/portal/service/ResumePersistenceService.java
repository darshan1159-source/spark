package com.studentresume.portal.service;

import com.studentresume.portal.model.ParseStatus;
import com.studentresume.portal.model.Resume;
import com.studentresume.portal.model.ResumeAccessDeniedException;
import com.studentresume.portal.model.ResumeData;
import com.studentresume.portal.model.ResumeSections;
import com.studentresume.portal.model.User;
import com.studentresume.portal.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the in-memory, session-scoped {@link ResumeData} working copy and the persisted
 * {@link Resume} row. The only attacker-controlled inputs anywhere in this class are the
 * {@code resumeId} parameters passed in from a path variable — {@code ResumeData.getId()} is
 * never client-suppliable, only ever set here after an ownership check already passed.
 *
 * <p>Also the sole gateway for the background CV-parsing flow's DB writes (create the pending
 * row, complete or fail it) — {@code ResumeParsingOrchestrator} depends on this, never on
 * {@link ResumeRepository} directly, keeping repository access confined to one service.
 */
@Service
public class ResumePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ResumePersistenceService.class);

    private final ResumeRepository resumeRepository;

    public ResumePersistenceService(ResumeRepository resumeRepository) {
        this.resumeRepository = resumeRepository;
    }

    /**
     * Upserts by {@code data.getId()}; on first save, binds the session copy to the new row.
     *
     * <p>Deliberately does NOT fetch the existing row before saving over it. A fetch-then-save
     * would re-read whatever version is CURRENTLY in the DB and use that for the optimistic-lock
     * check — which would always match itself, since it was just read, making a conflict between
     * two devices impossible to ever detect. Instead this constructs an entity carrying the
     * version {@code data} last knew about (from whenever this session loaded or last saved this
     * resume) and hands it to {@code save()}, which merges a non-new entity — JPA compares
     * that carried-over version against whatever's actually in the DB right now, and throws
     * {@code ObjectOptimisticLockingFailureException} if another device's save moved it on since.
     * Ownership is checked explicitly first ({@code existsByIdAndUserId}) since skipping the
     * fetch also skips the ownership check that fetch used to double as.
     *
     * <p>Uses {@code saveAndFlush}, not {@code save} — a plain {@code save()} inside a
     * {@code @Transactional} method doesn't necessarily execute the UPDATE (and Hibernate's
     * version-increment) until the transaction commits at method exit, so the entity handed
     * back can still show the OLD, pre-increment version. Without the explicit flush here,
     * {@code data.setVersion(saved.getVersion())} below would remember that stale value, and
     * this SAME session's very next save would incorrectly self-conflict against its own
     * just-completed save.
     */
    @Transactional
    public Resume saveCurrentResume(ResumeData data, User owner) {
        Resume resume = new Resume();
        if (data.getId() != null) {
            if (!resumeRepository.existsByIdAndUserId(data.getId(), owner.getId())) {
                throw new ResumeAccessDeniedException("Resume not found");
            }
            resume.setId(data.getId());
            resume.setVersion(data.getVersion());
        }

        resume.setUser(owner);
        resume.setTitle(deriveTitle(data));
        applyResumeData(resume, data);
        resume.setUpdatedAt(Instant.now());

        Resume saved = resumeRepository.saveAndFlush(resume);
        data.setId(saved.getId());
        data.setVersion(saved.getVersion()); // remember the new version for this session's NEXT save
        return saved;
    }

    /** Ownership-checked load; defensively copies each list, matching CvParsingService's copyResumeData(). */
    @Transactional(readOnly = true)
    public void loadIntoSession(Long resumeId, User owner, ResumeData target) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, owner.getId())
                .orElseThrow(() -> new ResumeAccessDeniedException("Resume not found"));

        target.setId(resume.getId());
        target.setVersion(resume.getVersion());
        target.setFullName(resume.getFullName());
        target.setEmail(resume.getEmail());
        target.setPhone(resume.getPhone());
        target.setLinkedinUrl(resume.getLinkedinUrl());
        target.setGithubUrl(resume.getGithubUrl());
        target.setSelectedTemplate(resume.getSelectedTemplate());

        ResumeSections s = resume.getSections();
        target.setEducation(new ArrayList<>(s.education()));
        target.setProjects(new ArrayList<>(s.projects()));
        target.setExperience(new ArrayList<>(s.experience()));
        target.setSkills(new ArrayList<>(s.skills()));
    }

    @Transactional(readOnly = true)
    public List<Resume> listForUser(User owner) {
        return resumeRepository.findByUserIdOrderByUpdatedAtDesc(owner.getId());
    }

    /** Ownership-checked single-resume read — reused by the dashboard's select-guard and status-poll endpoint. */
    @Transactional(readOnly = true)
    public Resume getForUser(Long id, User owner) {
        return resumeRepository.findByIdAndUserId(id, owner.getId())
                .orElseThrow(() -> new ResumeAccessDeniedException("Resume not found"));
    }

    @Transactional
    public void delete(Long resumeId, User owner) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, owner.getId())
                .orElseThrow(() -> new ResumeAccessDeniedException("Resume not found"));
        resumeRepository.delete(resume);
    }

    // -------------------------------------------------------------------------
    // Background CV-parsing flow
    // -------------------------------------------------------------------------

    /** Creates the placeholder row a background parse will later fill in — see {@code UploadController}. */
    @Transactional
    public Resume createPending(User owner) {
        Resume resume = new Resume();
        resume.setUser(owner);
        resume.setTitle("Parsing your resume…");
        resume.setParseStatus(ParseStatus.PENDING);
        resume.setUpdatedAt(Instant.now());
        return resumeRepository.save(resume);
    }

    /**
     * Writes the parsed content AND flips the row to READY in one transaction — a poll request
     * running concurrently can only ever observe the fully-pre-commit or fully-post-commit row,
     * never real content paired with a stale PENDING status or vice versa.
     */
    @Transactional
    public Resume completeParsing(Long resumeId, User owner, ResumeData parsed) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, owner.getId())
                .orElseThrow(() -> new ResumeAccessDeniedException("Resume not found"));
        applyResumeData(resume, parsed);
        resume.setTitle(deriveTitle(parsed));
        resume.setParseStatus(ParseStatus.READY);
        resume.setNotifiedReady(false);
        resume.setUpdatedAt(Instant.now());
        return resumeRepository.save(resume);
    }

    /**
     * Marks a pending row as failed. Deliberately tolerant of the row already being gone (the
     * student deleted it, or their whole account, while the background task was still running)
     * — logs and returns rather than throwing, since that's an expected outcome here, not a bug.
     */
    @Transactional
    public void failParsing(Long resumeId, User owner, String message) {
        resumeRepository.findByIdAndUserId(resumeId, owner.getId()).ifPresentOrElse(
                resume -> {
                    resume.setParseStatus(ParseStatus.FAILED);
                    resume.setParseError(message);
                    resume.setUpdatedAt(Instant.now());
                    resumeRepository.save(resume);
                },
                () -> log.info("failParsing: resume {} no longer exists (deleted mid-flight?) — nothing to update", resumeId)
        );
    }

    /**
     * If {@code resumeId} just became READY and hasn't been notified yet, claims that
     * notification (flips {@code notifiedReady}) and returns true. Deliberately separate from
     * reading/rendering the resume's current state — two browser tabs polling the same resume
     * must both keep seeing its current card correctly regardless of which one wins this claim.
     */
    @Transactional
    public boolean claimReadyNotification(Long resumeId, User owner) {
        return resumeRepository.findByIdAndUserId(resumeId, owner.getId())
                .filter(r -> r.getParseStatus() == ParseStatus.READY && !r.isNotifiedReady())
                .map(r -> {
                    r.setNotifiedReady(true);
                    resumeRepository.save(r);
                    return true;
                })
                .orElse(false);
    }

    /** Bulk version of {@link #claimReadyNotification} for a fresh full dashboard page load. */
    @Transactional
    public List<Resume> claimReadyNotifications(User owner) {
        List<Resume> justReady = resumeRepository.findByUserIdAndParseStatusAndNotifiedReadyFalse(owner.getId(), ParseStatus.READY);
        justReady.forEach(r -> r.setNotifiedReady(true));
        return resumeRepository.saveAll(justReady);
    }

    /**
     * Sweeps every row still PENDING at startup and fails them with {@code message} — called
     * once by {@code ResumeParsingOrchestrator} on {@code ApplicationReadyEvent}. Without this,
     * a row orphaned by a server restart (this app runs with devtools live-reload during
     * development, restarting on every code change) would stay stuck PENDING forever with no
     * background task left to ever finish it.
     */
    @Transactional
    public void recoverOrphanedPending(String message) {
        List<Resume> orphaned = resumeRepository.findByParseStatus(ParseStatus.PENDING);
        if (orphaned.isEmpty()) return;
        for (Resume resume : orphaned) {
            resume.setParseStatus(ParseStatus.FAILED);
            resume.setParseError(message);
            resume.setUpdatedAt(Instant.now());
        }
        resumeRepository.saveAll(orphaned);
        log.warn("Recovered {} orphaned PENDING resume(s) left over from a previous run", orphaned.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void applyResumeData(Resume resume, ResumeData data) {
        resume.setFullName(data.getFullName());
        resume.setEmail(data.getEmail());
        resume.setPhone(data.getPhone());
        resume.setLinkedinUrl(data.getLinkedinUrl());
        resume.setGithubUrl(data.getGithubUrl());
        resume.setSelectedTemplate(data.getSelectedTemplate());
        resume.setSections(new ResumeSections(
                data.getEducation(), data.getProjects(), data.getExperience(), data.getSkills()));
    }

    private String deriveTitle(ResumeData data) {
        return (data.getFullName() == null || data.getFullName().isBlank())
                ? "Untitled resume"
                : data.getFullName() + "'s Resume";
    }
}
