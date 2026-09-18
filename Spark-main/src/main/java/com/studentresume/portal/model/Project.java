package com.studentresume.portal.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One project entry.
 * The CEO specified a single {@code summary} field (dedicated projects section).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Project {
    /** Stable identity — see {@link Education#getId()} for why this exists. */
    private String id = UUID.randomUUID().toString();

    private String title     = "";
    private String techStack = "";
    /** Free-text project summary — the core field per CEO spec. */
    private String summary   = "";
}
