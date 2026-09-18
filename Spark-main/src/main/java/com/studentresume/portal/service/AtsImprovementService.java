package com.studentresume.portal.service;

import com.studentresume.portal.model.ImprovementTarget;
import com.studentresume.portal.model.Project;
import com.studentresume.portal.model.ResumeData;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, synchronous, no-AI scan for field-level ATS improvements.
 *
 * <p>Mirrors {@link AtsScoringService}'s per-check logic at field granularity, so every target
 * emitted here corresponds to a real point deduction visible in the ATS checklist. Contact Info
 * and Section Coverage are deliberately skipped — they require real personal data or whole new
 * entries the AI can't legitimately invent, and they're already visible in the always-present
 * ATS sidebar checklist.
 */
@Service
public class AtsImprovementService {

    private final AtsScoringService scoring;

    public AtsImprovementService(AtsScoringService scoring) {
        this.scoring = scoring;
    }

    public List<ImprovementTarget> identifyTargets(ResumeData data) {
        List<ImprovementTarget> targets = new ArrayList<>();

        List<FieldRef> orderedFields = new ArrayList<>();
        for (int i = 0; i < data.getProjects().size(); i++) {
            orderedFields.add(new FieldRef("projects", i, "summary", data.getProjects().get(i).getSummary()));
        }
        for (int i = 0; i < data.getExperience().size(); i++) {
            orderedFields.add(new FieldRef("experience", i, "description", data.getExperience().get(i).getDescription()));
        }
        for (int i = 0; i < data.getEducation().size(); i++) {
            String d = data.getEducation().get(i).getDescription();
            // Mirrors checkContentDepth: a blank Education description is a legitimate
            // omission, not thin content, so it isn't a target candidate at all.
            if (d != null && !d.isBlank()) {
                orderedFields.add(new FieldRef("education", i, "description", d));
            }
        }

        // Pass 1: replicate checkActionVerbs' global verb-repeat state in the exact same
        // traversal order, so a field is flagged here iff it actually cost points there.
        Set<String> seenVerbs = new HashSet<>();
        Map<FieldRef, Boolean> weakOrRepeat = new HashMap<>();
        for (FieldRef f : orderedFields) {
            boolean flagged = false;
            for (String bullet : scoring.bulletLinesOf(f.text())) {
                String stripped = scoring.stripBulletMarker(bullet);
                boolean weak = AtsScoringService.WEAK_BULLET_OPENERS.stream().anyMatch(stripped::startsWith);
                String verb = scoring.openingVerb(stripped);
                boolean repeat = !verb.isEmpty() && !seenVerbs.add(verb);
                if (weak || repeat) flagged = true;
            }
            weakOrRepeat.put(f, flagged);
        }

        // Pass 2: build one target per field, combining every flag that applies to it.
        for (FieldRef f : orderedFields) {
            List<String> bullets = scoring.bulletLinesOf(f.text());
            boolean thin      = scoring.wordCount(f.text()) < 10;
            boolean weakVerbs = weakOrRepeat.getOrDefault(f, false);
            boolean tooLong   = bullets.stream().anyMatch(b -> scoring.wordCount(b) > 30);
            // Flags only if the WHOLE field has zero quantified bullets — "any single bullet
            // lacks a number" would fire on almost every multi-bullet entry and be noise.
            boolean noMetrics = !bullets.isEmpty() && bullets.stream().noneMatch(this::hasDigit);

            List<String> reasons = new ArrayList<>();
            if (thin) {
                reasons.add("This entry is thin — expand it into 1-2 full sentences with concrete specifics.");
            }
            if (weakVerbs) {
                reasons.add("Replace weak/passive openers (e.g. \"worked on,\" \"responsible for\") " +
                        "and make sure no two bullets open with the same verb.");
            }
            if (tooLong) {
                reasons.add("Split any bullet over 30 words into a tighter, single-fact line.");
            }
            if (noMetrics) {
                reasons.add("Add at least one concrete number (%, count, time saved, users, team size). " +
                        "IMPORTANT: never invent a realistic-sounding statistic that isn't already implied " +
                        "by the text. Vague words like \"many,\" \"several,\" \"various,\" or \"multiple\" " +
                        "are NOT real numbers and do not justify guessing one — if there's no actual number " +
                        "to use, you MUST insert an explicit placeholder like \"[X]%\" or \"[X] users\" " +
                        "instead of a specific-sounding count.");
            }
            if (reasons.isEmpty()) continue;

            String instruction = String.join(" ", reasons) +
                    " Keep every fact that's already there — only rewrite wording and structure, " +
                    "do not add new facts, projects, or claims beyond what's already written.";

            targets.add(new ImprovementTarget(
                    f.section(), f.index(), f.fieldName(), label(f), f.text(), instruction, null));
        }

        // Skills: deterministic diff against every Project's tech stack. No AI involved —
        // the answer is already fully computed.
        Set<String> currentLower = new HashSet<>();
        for (String s : data.getSkills()) currentLower.add(s.trim().toLowerCase());

        LinkedHashSet<String> additions = new LinkedHashSet<>();
        for (Project p : data.getProjects()) {
            if (p.getTechStack() == null) continue;
            for (String tech : p.getTechStack().split(",")) {
                String trimmed = tech.trim();
                if (!trimmed.isEmpty() && !currentLower.contains(trimmed.toLowerCase())) {
                    additions.add(trimmed);
                }
            }
        }
        if (!additions.isEmpty()) {
            targets.add(new ImprovementTarget(
                    "skills", null, "skillsInput",
                    "Skills · from your project tech stacks",
                    null, null, new ArrayList<>(additions)));
        }

        return targets;
    }

    private boolean hasDigit(String bullet) {
        return bullet.chars().anyMatch(Character::isDigit);
    }

    private String label(FieldRef f) {
        String sectionLabel = switch (f.section()) {
            case "projects"   -> "Project";
            case "experience" -> "Experience";
            case "education"  -> "Education";
            default           -> f.section();
        };
        String fieldLabel = "summary".equals(f.fieldName()) ? "Summary" : "Description";
        return sectionLabel + " #" + (f.index() + 1) + " · " + fieldLabel;
    }

    private record FieldRef(String section, int index, String fieldName, String text) {}
}
