package com.studentresume.portal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One saved, persisted resume belonging to a {@link User}. Hybrid storage: simple scalar
 * fields are real columns; the nested Education/Projects/Experience/Skills lists are one
 * JSON column via {@link ResumeSectionsConverter} — see {@link ResumeSections}.
 *
 * <p>Deliberately {@code @Getter @Setter}, not Lombok {@code @Data} — see {@link User}'s
 * javadoc for why, doubly relevant here given the {@code @ManyToOne} relation.
 */
@Entity
@Table(name = "resumes")
@Getter
@Setter
@NoArgsConstructor
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title = "Untitled resume";

    private String fullName    = "";
    private String email       = "";
    private String phone       = "";
    private String linkedinUrl = "";
    private String githubUrl   = "";

    @Column(nullable = false)
    private String selectedTemplate = "ats";

    @Column(columnDefinition = "TEXT", nullable = false)
    @Convert(converter = ResumeSectionsConverter.class)
    private ResumeSections sections = ResumeSections.empty();

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * CV-upload background-parsing state. Explicit {@code columnDefinition} SQL defaults below
     * (not just Java-side field initializers) are required, not stylistic — {@code ddl-auto=update}
     * adds these columns via a plain {@code ALTER TABLE}, and SQLite rejects an {@code ALTER
     * TABLE ... ADD COLUMN} that's {@code NOT NULL} with no SQL-level default on a non-empty
     * table (this app's dev DB already has real rows). Mirrors how {@code sections} above already
     * pairs {@code columnDefinition} with {@code nullable = false}.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "TEXT default 'READY'")
    private ParseStatus parseStatus = ParseStatus.READY;

    /** True once the "your resume is ready" notification has been shown once. See ResumePersistenceService's claim*-methods. */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean notifiedReady = true;

    /** Set only when parseStatus == FAILED; a fixed, generic message — never raw exception text. */
    @Column(columnDefinition = "TEXT")
    private String parseError;

    /**
     * Optimistic-locking version, for the two-different-devices case (as opposed to the
     * two-tabs-same-browser case, which is a separate, in-memory problem — see ResumeData's
     * javadoc). Hibernate manages this automatically: every UPDATE it generates for this entity
     * carries a {@code WHERE version = ?} clause and bumps the value on success. If two devices
     * both loaded this row at version 5 and the first one's save lands, the row becomes version
     * 6 — the second device's save (still targeting {@code WHERE version = 5}) then matches zero
     * rows, and Hibernate throws {@code ObjectOptimisticLockingFailureException} on its own,
     * with no manual comparison code needed anywhere. Same SQLite migration-safety concern as
     * parseStatus above: needs an explicit SQL default, not just a Java field initializer.
     */
    @Version
    @Column(nullable = false, columnDefinition = "INTEGER default 0")
    private Long version;
}
