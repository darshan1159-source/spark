package com.studentresume.portal.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One education entry.
 * The CEO specified a single {@code description} field (no bullet list sub-fields).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Education {
    /**
     * Stable identity, assigned once at creation and never reassigned — lets a browser tab
     * address "this specific entry" even after another tab has inserted/removed entries and
     * shifted list positions around (positional index addressing used to mean a stale tab's
     * next edit could silently land on the wrong entry).
     */
    private String id = UUID.randomUUID().toString();

    private String institution = "";
    private String degree      = "";
    private String dates       = "";
    /** Free-text description / achievements for this education entry. */
    private String description = "";
}
