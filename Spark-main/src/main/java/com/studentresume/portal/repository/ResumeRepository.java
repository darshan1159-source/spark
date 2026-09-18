package com.studentresume.portal.repository;

import com.studentresume.portal.model.ParseStatus;
import com.studentresume.portal.model.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    List<Resume> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * The ownership check IS the query, not a fetch-then-compare step — no code path can
     * obtain a Resume without having already proven the caller owns it.
     */
    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    /**
     * Ownership check without fetching the row's current state — used where a fetch-then-save
     * would defeat the purpose (see {@code ResumePersistenceService.saveCurrentResume}'s
     * optimistic-locking comment): the point is comparing what the caller *last knew* against
     * what's actually in the DB, so nothing here should hand back a fresher copy to overwrite it.
     */
    boolean existsByIdAndUserId(Long id, Long userId);

    /** Every resume still awaiting its one-time "ready" notification, for one user. */
    List<Resume> findByUserIdAndParseStatusAndNotifiedReadyFalse(Long userId, ParseStatus parseStatus);

    /**
     * Global, no owner filter — an internal maintenance query (the startup orphaned-PENDING
     * recovery sweep in ResumeParsingOrchestrator), not something reachable from a user request.
     */
    List<Resume> findByParseStatus(ParseStatus parseStatus);
}
