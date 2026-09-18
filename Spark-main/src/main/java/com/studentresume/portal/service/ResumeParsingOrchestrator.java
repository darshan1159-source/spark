package com.studentresume.portal.service;

import com.studentresume.portal.model.ResumeData;
import com.studentresume.portal.model.User;
import com.studentresume.portal.repository.UserRepository;
import com.studentresume.portal.service.CvParsingService.ParseOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs the expensive part of CV parsing on a background thread, and recovers any resume left
 * stuck PENDING by a previous process (crash, kill, or a devtools live-reload restart — this app
 * runs with {@code spring-boot-devtools}, so every code-change restart during development is
 * exactly this scenario).
 *
 * <p>Depends on {@link CvParsingService} (the LLM work) and {@link ResumePersistenceService}
 * (the only gateway to {@code ResumeRepository} — this class never touches the repository
 * directly, preserving that boundary) plus {@link UserRepository}, needed only because a
 * background thread has no {@code HttpSession} to resolve the owner through {@code
 * CurrentUserProvider}.
 */
@Service
public class ResumeParsingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ResumeParsingOrchestrator.class);

    /** Fixed, generic — never raw exception text. Matches every other user-facing message in this app. */
    private static final String GENERIC_FAILURE_MESSAGE =
            "We couldn't parse this resume automatically. Please delete it and try uploading again.";

    private static final String RESTART_FAILURE_MESSAGE =
            "Interrupted by a server restart. Please delete and re-upload.";

    private final CvParsingService cvParsingService;
    private final ResumePersistenceService resumePersistenceService;
    private final UserRepository userRepository;

    public ResumeParsingOrchestrator(CvParsingService cvParsingService,
                                      ResumePersistenceService resumePersistenceService,
                                      UserRepository userRepository) {
        this.cvParsingService = cvParsingService;
        this.resumePersistenceService = resumePersistenceService;
        this.userRepository = userRepository;
    }

    /**
     * @param rawText  already validated by {@code CvParsingService.runQuickGate} — this method
     *                 only does the expensive structured-extraction phase
     * @param resumeId the pending row (see {@code ResumePersistenceService.createPending}) this
     *                 result gets written into
     * @param userId   resolved here, not passed as a {@code User}, since the caller (a request
     *                 thread) shouldn't hand a JPA entity across thread boundaries
     */
    @Async("resumeParsingExecutor")
    public void processResumeAsync(String rawText, Long resumeId, Long userId) {
        try {
            User owner = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalStateException("User " + userId + " no longer exists"));

            ResumeData scratch = new ResumeData(); // plain POJO — safe to `new` outside the session-scoped proxy
            scratch.setId(resumeId);
            ParseOutcome outcome = cvParsingService.extractStructured(rawText, scratch);

            if (outcome == ParseOutcome.SUCCESS) {
                resumePersistenceService.completeParsing(resumeId, owner, scratch);
            } else {
                resumePersistenceService.failParsing(resumeId, owner, GENERIC_FAILURE_MESSAGE);
            }
        } catch (Exception ex) {
            // Covers the user/row having been deleted mid-flight, or anything else unexpected —
            // failParsing itself already tolerates a missing row, so this is a safety net, not
            // the primary path for that case.
            log.error("Background resume parsing failed for resume {}: {}", resumeId, ex.getMessage(), ex);
            safelyMarkFailed(resumeId, userId);
        }
    }

    private void safelyMarkFailed(Long resumeId, Long userId) {
        try {
            userRepository.findById(userId)
                    .ifPresent(owner -> resumePersistenceService.failParsing(resumeId, owner, GENERIC_FAILURE_MESSAGE));
        } catch (Exception ex) {
            log.error("Could not even mark resume {} as failed: {}", resumeId, ex.getMessage(), ex);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverOrphanedPendingResumes() {
        resumePersistenceService.recoverOrphanedPending(RESTART_FAILURE_MESSAGE);
    }
}
