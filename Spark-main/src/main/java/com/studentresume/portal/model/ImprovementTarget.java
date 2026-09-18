package com.studentresume.portal.model;

import java.util.List;

/**
 * One field-level ATS improvement opportunity, produced by
 * {@link com.studentresume.portal.service.AtsImprovementService#identifyTargets}.
 *
 * <p>For every section except "skills", {@code instruction} is non-null and the frontend calls
 * the existing {@code POST /api/assistant/suggest} with {@code currentText} + {@code instruction}
 * to obtain the rewrite. The "skills" target skips that step entirely — {@code suggestedAdditions}
 * is already the final, deterministic answer.
 *
 * @param section            "education" | "projects" | "experience" | "skills"
 * @param fieldIndex         index within that section's list; null only for the skills target
 * @param fieldName          the live DOM field's exact {@code name} attribute — "description",
 *                           "summary", or "skillsInput" — used by {@code resolveJumpTarget()}
 * @param label              human-readable, e.g. "Project #1 · Summary"
 * @param currentText        current field value; null for the skills target
 * @param instruction        AI rewrite instruction; null for the skills target
 * @param suggestedAdditions skill names to add (from Project tech stacks); null/empty otherwise
 */
public record ImprovementTarget(
        String section,
        Integer fieldIndex,
        String fieldName,
        String label,
        String currentText,
        String instruction,
        List<String> suggestedAdditions
) {}
