package com.studentresume.portal.model;

/** Lifecycle of a {@link Resume} row created via CV upload — see {@code ResumeParsingOrchestrator}. */
public enum ParseStatus {
    /** Not from an active upload, or fully parsed and ready to edit. Default for every normal resume. */
    READY,
    /** Background extraction is still running. */
    PENDING,
    /** Background extraction failed — see {@link Resume#getParseError()}. */
    FAILED
}
