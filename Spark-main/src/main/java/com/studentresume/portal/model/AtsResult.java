package com.studentresume.portal.model;

import java.util.List;

/**
 * Immutable result produced by {@link com.studentresume.portal.service.AtsScoringService}.
 *
 * @param totalScore 0–100 weighted ATS score
 * @param checks     list of all checks with partial credit, reasons, and fix tips
 */
public record AtsResult(int totalScore, List<AtsCheckResult> checks) {

    /**
     * A single ATS check result with partial credit support.
     *
     * @param checkName     name of the check (e.g., "Contact info")
     * @param pointsEarned  points earned (0 to pointsPossible)
     * @param pointsPossible maximum points for this check
     * @param status        "pass" | "partial" | "fail"
     * @param reason        built from the student's actual content
     * @param fixTip        concrete next action; empty string if already passing
     */
    public record AtsCheckResult(
            String checkName,
            int pointsEarned,
            int pointsPossible,
            String status,
            String reason,
            String fixTip
    ) {}
}
