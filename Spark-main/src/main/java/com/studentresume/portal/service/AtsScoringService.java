package com.studentresume.portal.service;

import com.studentresume.portal.model.AtsResult;
import com.studentresume.portal.model.AtsResult.AtsCheckResult;
import com.studentresume.portal.model.Education;
import com.studentresume.portal.model.ResumeData;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Heuristic ATS scoring rubric with partial credit support.
 *
 * <p>Eight checks with defined weights sum to 100. Fully synchronous, no external calls —
 * so the live badge in the builder can recalculate on every htmx partial update without
 * any perceptible latency.
 *
 * <p>Each check provides specific reasons and fix tips based on the student's actual content,
 * giving actionable feedback rather than generic advice.
 */
@Service
public class AtsScoringService {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public AtsResult score(ResumeData data) {
        List<AtsCheckResult> checks = List.of(
                checkContactInfo(data),
                checkSectionCoverage(data),
                checkSkillsDepth(data),
                checkContentDepth(data),
                checkQuantifiedImpact(data),
                checkActionVerbs(data),
                checkScannability(data),
                checkOverallLength(data)
        );
        int total = checks.stream().mapToInt(AtsCheckResult::pointsEarned).sum();
        return new AtsResult(total, checks);
    }

    // -------------------------------------------------------------------------
    // Individual checks with partial credit
    // -------------------------------------------------------------------------

    private AtsCheckResult checkContactInfo(ResumeData data) {
        int points = 0;
        List<String> missing = new ArrayList<>();
        if (data.getFullName() != null && !data.getFullName().isBlank()) points += 3;
        else missing.add("name");
        if (data.getEmail() != null && !data.getEmail().isBlank()) points += 3;
        else missing.add("email");
        if (data.getPhone() != null && !data.getPhone().isBlank()) points += 2;
        else missing.add("phone");
        boolean hasLinkedin = data.getLinkedinUrl() != null && !data.getLinkedinUrl().isBlank();
        boolean hasGithub   = data.getGithubUrl()   != null && !data.getGithubUrl().isBlank();
        if (hasLinkedin || hasGithub) points += 2;
        else missing.add("a LinkedIn or GitHub link");

        String reason = missing.isEmpty()
                ? "All contact fields are filled in."
                : "Missing: " + String.join(", ", missing) + ".";
        String fix = missing.isEmpty() ? "" : "Add your " + String.join(" and ", missing) + " at the top of the resume.";

        return new AtsCheckResult("Contact info", points, 10, statusFor(points / 10.0), reason, fix);
    }

    private AtsCheckResult checkSectionCoverage(ResumeData data) {
        int points = 0;
        List<String> missing = new ArrayList<>();
        if (!data.getEducation().isEmpty()) points += 5;
        else missing.add("Education");
        if (!data.getSkills().isEmpty()) points += 5;
        else missing.add("Skills");
        if (!data.getProjects().isEmpty() || !data.getExperience().isEmpty()) points += 5;
        else missing.add("Projects or Experience");
        
        String reason = missing.isEmpty()
                ? "Education, Skills, and Projects/Experience are all present."
                : "Missing section(s): " + String.join(", ", missing) + ".";
        String fix = missing.isEmpty() ? "" : "Add at least one entry under " + String.join(" and ", missing) + ".";
        
        return new AtsCheckResult("Section coverage", points, 15, statusFor(points / 15.0), reason, fix);
    }

    private AtsCheckResult checkSkillsDepth(ResumeData data) {
        int count = data.getSkills().size();
        int points = Math.min(10, count * 2);
        String reason = count == 0
                ? "No skills listed."
                : count + " skill" + (count == 1 ? "" : "s") + " listed" + (count < 5 ? " — aim for at least 5." : ".");
        String fix = count < 5 ? "List every relevant language, framework, and tool — don't undersell what you actually used." : "";
        
        return new AtsCheckResult("Skills depth", points, 10, statusFor(points / 10.0), reason, fix);
    }

    /**
     * Content depth deliberately excludes blank Education descriptions: that field is optional
     * supplementary detail (GPA, coursework, achievements), so leaving it empty is a legitimate
     * choice, not thin content. A Project or Experience entry with no description is still a real
     * gap and stays in the count. An Education entry the student DID write something for is still
     * checked for thinness like any other.
     */
    private AtsCheckResult checkContentDepth(ResumeData data) {
        List<String> all = new ArrayList<>();
        data.getProjects().forEach(p -> all.add(p.getSummary()));
        data.getExperience().forEach(e -> all.add(e.getDescription()));
        data.getEducation().stream()
                .map(Education::getDescription)
                .filter(d -> d != null && !d.isBlank())
                .forEach(all::add);

        long thin = all.stream().filter(d -> wordCount(d) < 10).count();
        double ratio = all.isEmpty() ? 0 : 1 - ((double) thin / all.size());
        int points = (int) Math.round(ratio * 15);
        String reason = thin == 0
                ? "All entries have a solid amount of detail."
                : thin + " of " + all.size() + " entries are under 10 words.";
        String fix = thin > 0 ? "Expand the thin entries into 1-2 full sentences each." : "";
        
        return new AtsCheckResult("Content depth", points, 15, statusFor(ratio), reason, fix);
    }

    private AtsCheckResult checkQuantifiedImpact(ResumeData data) {
        List<String> bullets = allBulletLines(data);
        long withNumbers = bullets.stream().filter(d -> d.chars().anyMatch(Character::isDigit)).count();
        double ratio = bullets.isEmpty() ? 0 : (double) withNumbers / bullets.size();
        int points = (int) Math.round(ratio * 15);
        String reason = withNumbers == 0
                ? "None of your bullet points include a number."
                : withNumbers + " of " + bullets.size() + " bullet points include a number.";
        String fix = "Add a metric where you can — team size, users, time saved, percentage improved.";

        return new AtsCheckResult("Quantified impact", points, 15, statusFor(ratio), reason, fix);
    }

    static final Set<String> WEAK_BULLET_OPENERS = Set.of(
            "worked on", "responsible for", "was responsible for", "were responsible for",
            "helped with", "helped to", "involved in", "assisted with", "participated in",
            "tasked with", "in charge of", "duties included"
    );

    /**
     * Scores bullets on two dimensions recruiters flag together: weak/passive openers, and
     * reusing the same opening verb across bullets. A bullet loses credit for either — first
     * use of a verb is fine, a second bullet reusing it is what signals a lack of variety.
     */
    private AtsCheckResult checkActionVerbs(ResumeData data) {
        List<String> bullets = allBulletLines(data);
        Set<String> seenVerbs = new HashSet<>();
        long strong = 0;
        boolean anyWeak = false;
        boolean anyRepeat = false;

        for (String bullet : bullets) {
            String stripped = stripBulletMarker(bullet);
            boolean weak = WEAK_BULLET_OPENERS.stream().anyMatch(stripped::startsWith);
            String openingVerb = openingVerb(stripped);
            boolean repeat = !openingVerb.isEmpty() && !seenVerbs.add(openingVerb);
            if (weak) anyWeak = true;
            if (repeat) anyRepeat = true;
            if (!weak && !repeat) strong++;
        }

        double ratio = bullets.isEmpty() ? 0 : (double) strong / bullets.size();
        int points = (int) Math.round(ratio * 15);

        String reason;
        if (ratio >= 0.999) {
            reason = "All bullet points open with a strong, varied action verb.";
        } else if (anyWeak && anyRepeat) {
            reason = "Some bullet points open with a weak phrase, and some repeat a verb already used elsewhere.";
        } else if (anyWeak) {
            reason = "Some bullet points open with a weak phrase like \"worked on\" or \"responsible for.\"";
        } else {
            reason = "Some bullet points repeat the same opening verb as another bullet — vary your wording.";
        }
        String fix = "Rewrite weak openers as direct actions, and avoid reusing the same verb twice — try " +
                "\"Built,\" \"Led,\" \"Designed,\" \"Streamlined,\" \"Automated.\" The AI assistant's " +
                "\"Make it punchier\" button does this in one click.";

        return new AtsCheckResult("Action verbs", points, 15, statusFor(ratio), reason, fix);
    }

    private AtsCheckResult checkScannability(ResumeData data) {
        List<String> bullets = allBulletLines(data);
        long tooLong = bullets.stream().filter(d -> wordCount(d) > 30).count();
        double ratio = bullets.isEmpty() ? 1 : 1 - ((double) tooLong / bullets.size());
        int points = (int) Math.round(ratio * 10);
        String reason = tooLong == 0
                ? "Bullet points are scannable, appropriately concise."
                : tooLong + " bullet point" + (tooLong == 1 ? " is" : "s are") + " running long (30+ words) — recruiters skim, not read.";
        String fix = tooLong > 0 ? "Split long bullets into two tighter ones, or cut to the single strongest fact." : "";

        return new AtsCheckResult("Scannability", points, 10, statusFor(ratio), reason, fix);
    }

    private AtsCheckResult checkOverallLength(ResumeData data) {
        int totalWords = allDescriptions(data).stream()
                .filter(Objects::nonNull)
                .mapToInt(this::wordCount)
                .sum();
        int points;
        String reason, fix = "";
        
        if (totalWords < 80) {
            points = (int) Math.round((totalWords / 80.0) * 10);
            reason = "Resume content is quite short (" + totalWords + " words) — likely to look sparse.";
            fix = "Add more detail to your projects and experience sections.";
        } else if (totalWords > 600) {
            points = 5;
            reason = "Resume content is long (" + totalWords + " words) — consider tightening it.";
            fix = "Cut anything that isn't relevant or impactful.";
        } else {
            points = 10;
            reason = "Resume length (" + totalWords + " words) is in a healthy range.";
        }
        
        return new AtsCheckResult("Overall length", points, 10, statusFor(points / 10.0), reason, fix);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String statusFor(double ratio) {
        if (ratio >= 0.9) return "pass";
        if (ratio >= 0.4) return "partial";
        return "fail";
    }

    private List<String> allDescriptions(ResumeData data) {
        List<String> all = new ArrayList<>();
        data.getProjects().forEach(p -> all.add(p.getSummary()));
        data.getExperience().forEach(e -> all.add(e.getDescription()));
        data.getEducation().forEach(ed -> all.add(ed.getDescription()));
        return all;
    }

    /**
     * Flattens every entry into its individual bullet/sentence lines. A project or experience
     * entry can now hold several "• " bullets in one field (joined by real newlines); checks
     * that report "X of Y bullet points" need to count at that granularity; a field with no
     * bullet markers (a single-sentence description) still yields exactly one line here.
     */
    private List<String> allBulletLines(ResumeData data) {
        List<String> lines = new ArrayList<>();
        for (String d : allDescriptions(data)) {
            lines.addAll(bulletLinesOf(d));
        }
        return lines;
    }

    /** Splits one field's raw text into its individual bullet/sentence lines (never null). */
    List<String> bulletLinesOf(String description) {
        List<String> lines = new ArrayList<>();
        if (description == null) return lines;
        for (String line : description.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /** Strips a leading bullet marker (e.g. "• ") so the remaining text can be matched against a phrase. */
    String stripBulletMarker(String line) {
        return line.replaceFirst("^[•*\\-]\\s*", "").trim().toLowerCase();
    }

    int wordCount(String s) {
        if (s == null || s.isBlank()) return 0;
        return s.trim().split("\\s+").length;
    }

    /**
     * Common adverbs/intensifiers that precede the real verb in a bullet ("Successfully led...",
     * "Seamlessly integrated..."). Skipped so verb-variety detection compares actual verbs
     * instead of collapsing distinct verbs that share an intensifier, or missing a real repeat
     * where only one instance happens to carry one.
     */
    private static final Set<String> LEADING_ADVERBS = Set.of(
            "successfully", "effectively", "efficiently", "actively", "proactively",
            "consistently", "independently", "collaboratively", "seamlessly",
            "strategically", "rapidly", "quickly", "carefully", "thoroughly",
            "closely", "personally", "directly", "continuously", "significantly"
    );

    /** First non-adverb word of {@code s} — the bullet's actual opening verb, lowercased. */
    String openingVerb(String s) {
        for (String word : s.trim().split("\\s+")) {
            String cleaned = word.toLowerCase().replaceAll("[^a-z]", "");
            if (!cleaned.isEmpty() && !LEADING_ADVERBS.contains(cleaned)) {
                return cleaned;
            }
        }
        return "";
    }
}
