package com.studentresume.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentresume.portal.model.Education;
import com.studentresume.portal.model.Experience;
import com.studentresume.portal.model.Project;
import com.studentresume.portal.model.ResumeData;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Parses an uploaded PDF or DOCX resume into a {@link ResumeData}.
 *
 * <p>Split into two phases, called from different places: {@link #runQuickGate} (fast, no more
 * than one LLM call, called synchronously from the upload request) decides whether the file is
 * even worth the expensive part; {@link #extractStructured} (the actual multi-call structured
 * extraction, 20-90+ seconds) runs on a background thread — see {@code ResumeParsingOrchestrator}.
 * Takes raw {@code byte[]} rather than a {@code MultipartFile} throughout because the latter's
 * backing stream/temp file is only valid for the life of the original HTTP request, which the
 * background phase deliberately outlives.
 *
 * <p>Reliability bar is intentionally low: the student reviews and edits before
 * exporting, so a rough-but-mostly-right prefill is a win.
 */
@Service
public class CvParsingService {

    private static final Logger log = LoggerFactory.getLogger(CvParsingService.class);

    private final RestTemplate  restTemplate;
    private final ObjectMapper  objectMapper;
    private final String        ollamaBaseUrl;
    private final String        ollamaModel;

    public CvParsingService(RestTemplate restTemplate,
                            @Value("${ollama.base-url}") String ollamaBaseUrl,
                            @Value("${ollama.model}")    String ollamaModel) {
        this.restTemplate  = restTemplate;
        this.objectMapper  = new ObjectMapper();
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaModel   = ollamaModel;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Result of {@link #extractStructured}. The gate below already ruled out "not a resume". */
    public enum ParseOutcome { SUCCESS, PARSE_FAILED }

    /** Result of {@link #runQuickGate}. */
    public enum GateOutcome { ACCEPTED, NOT_A_RESUME, TECHNICAL_FAILURE }

    /** {@code rawText} is populated only when {@code outcome == ACCEPTED}. */
    public record GateResult(GateOutcome outcome, String rawText) {}

    /** Below this many characters, extracted text is treated as unreadable rather than sent to the LLM. */
    private static final int MIN_RESUME_TEXT_LENGTH = 100;

    /**
     * Above this many characters, extracted text is truncated before it ever reaches a prompt.
     * The multipart layer already caps uploads at 10MB (see application.properties) — nowhere
     * near enough to protect the LLM call, since a 10MB PDF/DOCX can still extract to hundreds
     * of thousands of characters of text, risking a very slow generation or silent truncation
     * by the model's own context window. ~12,000 characters comfortably covers even a long
     * multi-page academic CV.
     */
    private static final int MAX_RESUME_TEXT_LENGTH = 12_000;

    /**
     * Fast, synchronous check: is this file even worth the expensive extraction below? Text
     * extraction + magic-byte validation + the length check are pure Java (no LLM); the
     * {@link #looksLikeResume} classification is one short LLM call. Typically well under 3s
     * total — safe to call directly from the upload request before redirecting.
     *
     * @return a {@link GateResult} whose {@code outcome} is {@code ACCEPTED} (with
     *         {@code rawText} populated, ready for {@link #extractStructured}),
     *         {@code NOT_A_RESUME}, or {@code TECHNICAL_FAILURE} (bad magic bytes, corrupted
     *         file, unsupported type, or the classification call itself failing)
     */
    public GateResult runQuickGate(byte[] fileBytes, String originalFilename) {
        try {
            String rawText = extractText(fileBytes, originalFilename);
            log.debug("Extracted {} chars from uploaded file", rawText.length());
            if (rawText.length() > MAX_RESUME_TEXT_LENGTH) {
                rawText = rawText.substring(0, MAX_RESUME_TEXT_LENGTH);
            }

            // A near-empty extraction (scanned/photographed resume saved as PDF, no real text
            // layer) or text that doesn't read as resume-shaped is rejected here, before
            // spending any time on the expensive structured extraction.
            if (rawText.trim().length() < MIN_RESUME_TEXT_LENGTH || !looksLikeResume(rawText)) {
                return new GateResult(GateOutcome.NOT_A_RESUME, null);
            }
            return new GateResult(GateOutcome.ACCEPTED, rawText);
        } catch (Exception ex) {
            log.error("CV quick-gate check failed: {}", ex.getMessage(), ex);
            return new GateResult(GateOutcome.TECHNICAL_FAILURE, null);
        }
    }

    /**
     * The expensive part: structured extraction from already-gated {@code rawText} (see
     * {@link #runQuickGate}) into {@code target}. Multiple sequential Ollama calls — 20-90+
     * seconds typical, run this on a background thread (see {@code ResumeParsingOrchestrator}),
     * never on a request thread.
     *
     * @return {@link ParseOutcome#SUCCESS} if parsing succeeded (even partially), or
     *         {@link ParseOutcome#PARSE_FAILED} on any hard failure
     */
    public ParseOutcome extractStructured(String rawText, ResumeData target) {
        try {
            // Schema validity retry (already built)
            ResumeData result = extractWithSchemaRetry(rawText);

            // Content quality retry (new addition)
            result = extractWithQualityRetry(rawText, result);

            // Last line of defense: never let the format-illustration text reach the student,
            // even if the model copied it on both the original attempt and the retry.
            sanitizePlaceholderCopies(result);

            // Small models inconsistently duplicate a "Projects" entry into "experience" too —
            // observed even with explicit heading-based instructions telling it not to. Since
            // this is the most damaging failure mode (garbage rows the student didn't write),
            // catch it deterministically rather than depending on the model getting it right.
            removeExperienceEntriesDuplicatingProjects(result);

            // Copy result to target
            copyResumeData(result, target);
            return ParseOutcome.SUCCESS;
        } catch (Exception ex) {
            log.error("CV structured extraction failed: {}", ex.getMessage(), ex);
            return ParseOutcome.PARSE_FAILED;
        }
    }

    /**
     * Quick LLM gate: does this extracted text actually read as resume/CV content? Tried a
     * local keyword/pattern heuristic first (no LLM call, near-instant) — but it scored a job
     * posting ("...3+ years of experience... skills in Java... send your resume to
     * careers@...") as resume-shaped, since "experience" + "skills" + an email address alone
     * were enough to cross the threshold. A recruiter-facing document mentioning experience and
     * skills a lot is exactly the kind of false positive this gate exists to catch, and no
     * amount of keyword tuning reliably distinguishes "a document ABOUT a job" from "a document
     * listing MY OWN history" — that's a semantic distinction, not a lexical one.
     *
     * <p>The LLM call costs roughly 1s more than the heuristic in practice — most of the
     * heuristic's own ~700ms is fixed overhead (text extraction, HTTP round-trip) rather than
     * the scoring itself, so the real gap is smaller than it first looks. Against a full accept
     * path that already takes 45-60s for the real extraction, ~1s for a semantically reliable
     * gate is cheap. Deliberately conservative either way — only rejects on a clear "NO" from
     * the model; anything ambiguous, garbled, or unparseable falls through to the full
     * extraction rather than blocking a legitimate upload over a flaky one-word classification.
     */
    private boolean looksLikeResume(String rawText) throws Exception {
        // A short excerpt is plenty to tell "resume-shaped" from "not" — no need to spend
        // tokens/time on the whole document for this gate.
        String excerpt = rawText.length() > 2000 ? rawText.substring(0, 2000) : rawText;
        String prompt = """
                Look at the text below, extracted from an uploaded document. Decide whether it \
                reads as a resume/CV — a document listing a person's OWN work experience, \
                education, skills, or projects for a job application — as opposed to, say, a job \
                posting/description, a cover letter, or an unrelated document that merely \
                mentions similar words.

                Answer with exactly one word: YES or NO.

                Text:
                ---
                %s
                ---
                """.formatted(excerpt);

        String response = callOllamaWithPrompt(prompt).trim().toUpperCase();
        log.debug("Resume-shape classification: {}", response);
        return !response.startsWith("NO");
    }

    // -------------------------------------------------------------------------
    // Step 1 — Text extraction
    // -------------------------------------------------------------------------

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04}; // DOCX is a ZIP container ("PK\x03\x04")

    private String extractText(byte[] fileBytes, String originalFilename) throws Exception {
        if (originalFilename == null) throw new IllegalArgumentException("File has no name");

        String lower = originalFilename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            if (!startsWithMagic(fileBytes, PDF_MAGIC)) {
                throw new IllegalArgumentException("File has a .pdf name but isn't actually a PDF: " + originalFilename);
            }
            return extractPdf(new ByteArrayInputStream(fileBytes));
        } else if (lower.endsWith(".docx")) {
            // Only confirms it's a genuine ZIP container (what every DOCX/OOXML file is) — not
            // that its internal structure is specifically a Word document. A full check would
            // mean inspecting the ZIP's [Content_Types].xml, which is more than this gate needs:
            // POI's own parsing below already fails cleanly (caught by runQuickGate's catch-all)
            // on a ZIP that isn't a real DOCX.
            if (!startsWithMagic(fileBytes, ZIP_MAGIC)) {
                throw new IllegalArgumentException("File has a .docx name but isn't actually a DOCX: " + originalFilename);
            }
            return extractDocx(new ByteArrayInputStream(fileBytes));
        } else {
            throw new IllegalArgumentException("Unsupported file type: " + originalFilename);
        }
    }

    private boolean startsWithMagic(byte[] fileBytes, byte[] magic) {
        if (fileBytes.length < magic.length) return false;
        return Arrays.equals(fileBytes, 0, magic.length, magic, 0, magic.length);
    }

    private String extractPdf(InputStream is) throws Exception {
        try (PDDocument doc = PDDocument.load(is.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String extractDocx(InputStream is) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph para : doc.getParagraphs()) {
                sb.append(para.getText()).append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Step 2 — LLM structured extraction
    // -------------------------------------------------------------------------

    private String callOllama(String rawText) throws Exception {
        String prompt = buildPrompt(rawText);

        Map<String, Object> body = Map.of(
                "model",  ollamaModel,
                "prompt", prompt,
                "stream", false
        );

        String url      = ollamaBaseUrl + "/api/generate";
        String response = restTemplate.postForObject(url, body, String.class);

        JsonNode root = objectMapper.readTree(response);
        return root.path("response").asText();
    }

    /**
     * A fragment unique to {@link #PROJECT_SUMMARY_FORMAT_INSTRUCTIONS}'s format illustration.
     * Small models occasionally echo a worked example back verbatim instead of extracting real
     * content; if this fragment shows up in the model's output, {@link #sanitizePlaceholderCopies}
     * treats that project's summary as if the model had produced nothing.
     */
    private static final String PLACEHOLDER_COPY_MARKER = "peer-to-peer marketplace app for students";

    private static final String PROJECT_SUMMARY_FORMAT_INSTRUCTIONS = """
            For each project, fill "summaryBullets" with 2-4 short facts as separate JSON \
            array strings — e.g. one for what was built, one for the technologies used, one \
            for any measurable outcome or scale mentioned in the source text (users, \
            performance, placement, etc.). Each array element is a single plain-text fact \
            with no leading bullet character or numbering — just the words of the fact itself.

            Base every fact ONLY on what actually appears in the raw resume text below. If the \
            text does not give you enough real information about a project, set \
            "summaryBullets" to an empty array [] — never guess, invent details, or fall back \
            to the example below.

            FORMAT EXAMPLE ONLY — these are not from the user's resume. Never output these \
            facts, copy them, or paraphrase them into your answer:
            "summaryBullets": ["Built a %s using React and Node", "Reached over 400 users across two campuses"]
            """.formatted(PLACEHOLDER_COPY_MARKER);

    private static final String EXPERIENCE_CLASSIFICATION_INSTRUCTIONS = """
            The raw resume text below is organized into sections with headings — use ONLY the \
            heading above each block of text to decide where it goes; do not judge by wording \
            or content similarity:
            - A section headed "Experience", "Work Experience", "Internship", "Internships", \
              "Training", or similar → each entry goes into "experience". Set "type" to \
              "Internship" if that section (or the entry) is about an internship, "Training" if \
              it's about a training/trainee program, or "" for a plain job.
            - A section headed "Projects" → each entry goes into "projects", never also into \
              "experience" — even if it mentions measurable results or reads like an accomplishment.
            - A section headed "Certificates", "Certifications", "Achievements", "Awards", or \
              similar → skip these entirely. Do not include them anywhere in the JSON output.
            Never move an entry into a different array than the one its own section heading \
            indicates, and never split one entry into several or merge two entries together.

            Every experience entry MUST have a non-empty "title", even for internships and \
            training programs that aren't phrased as a job title in the source text. If there's \
            no explicit title, build one from what the entry actually covers — e.g. "Completed \
            a 3-month Full Stack Web Development training program" becomes the title "Full \
            Stack Web Development Training". Never leave "title" as an empty string when a \
            company or program name is present.
            """;

    private String buildPrompt(String rawText) {
        return """
                You are a resume parser. Extract structured resume information from the raw text below.
                Return ONLY a valid JSON object matching this exact schema — no markdown fences, no explanation:

                {
                  "fullName": "",
                  "email": "",
                  "phone": "",
                  "linkedinUrl": "",
                  "githubUrl": "",
                  "skills": ["skill1", "skill2"],
                  "education": [
                    { "institution": "", "degree": "", "dates": "", "description": "" }
                  ],
                  "projects": [
                    { "title": "", "techStack": "", "summaryBullets": [] }
                  ],
                  "experience": [
                    { "title": "", "company": "", "dates": "", "description": "", "type": "" }
                  ]
                }

                %s
                %s
                If a field is not found, use an empty string or empty array.
                Raw resume text:
                ---
                %s
                ---
                """.formatted(PROJECT_SUMMARY_FORMAT_INSTRUCTIONS, EXPERIENCE_CLASSIFICATION_INSTRUCTIONS, rawText);
    }

    private String buildPromptWithFeedback(String rawText, List<String> issues) {
        StringBuilder sb = new StringBuilder("""
                You are a resume parser. Extract structured resume information from the raw text below.
                Return ONLY a valid JSON object matching this exact schema — no markdown fences, no explanation:

                {
                  "fullName": "",
                  "email": "",
                  "phone": "",
                  "linkedinUrl": "",
                  "githubUrl": "",
                  "skills": ["skill1", "skill2"],
                  "education": [
                    { "institution": "", "degree": "", "dates": "", "description": "" }
                  ],
                  "projects": [
                    { "title": "", "techStack": "", "summaryBullets": [] }
                  ],
                  "experience": [
                    { "title": "", "company": "", "dates": "", "description": "", "type": "" }
                  ]
                }

                """);
        sb.append(PROJECT_SUMMARY_FORMAT_INSTRUCTIONS);
        sb.append(EXPERIENCE_CLASSIFICATION_INSTRUCTIONS);

        if (issues != null && !issues.isEmpty()) {
            sb.append("\nYour previous extraction had these specific problems — fix them:\n");
            issues.forEach(issue -> sb.append("- ").append(issue).append("\n"));
        }

        sb.append("""

                If a field is not found, use an empty string or empty array.
                Raw resume text:
                ---
                %s
                ---
                """.formatted(rawText));

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Step 3 — Map JSON → bean
    // -------------------------------------------------------------------------

    private void mapJsonToBean(String json, ResumeData target) throws Exception {
        // Extract the JSON block even if the LLM leaked some surrounding text
        int start = json.indexOf('{');
        int end   = json.lastIndexOf('}');
        if (start == -1 || end == -1) throw new IllegalStateException("No JSON object in LLM response");
        String cleanJson = json.substring(start, end + 1);

        JsonNode root = objectMapper.readTree(cleanJson);

        target.setFullName(text(root, "fullName"));
        target.setEmail(text(root, "email"));
        target.setPhone(text(root, "phone"));
        target.setLinkedinUrl(text(root, "linkedinUrl"));
        target.setGithubUrl(text(root, "githubUrl"));

        // Skills
        List<String> skills = new ArrayList<>();
        JsonNode skillsNode = root.path("skills");
        if (skillsNode.isArray()) {
            skillsNode.forEach(n -> skills.add(n.asText()));
        }
        target.setSkills(skills);

        // Education
        List<Education> eduList = new ArrayList<>();
        JsonNode eduNode = root.path("education");
        if (eduNode.isArray()) {
            for (JsonNode e : eduNode) {
                eduList.add(new Education(
                        UUID.randomUUID().toString(),
                        text(e, "institution"), text(e, "degree"),
                        text(e, "dates"),       text(e, "description")));
            }
        }
        target.setEducation(eduList);

        // Projects
        List<Project> projectList = new ArrayList<>();
        JsonNode projNode = root.path("projects");
        if (projNode.isArray()) {
            for (JsonNode p : projNode) {
                projectList.add(new Project(
                        UUID.randomUUID().toString(),
                        text(p, "title"), text(p, "techStack"), extractProjectSummary(p)));
            }
        }
        target.setProjects(projectList);

        // Experience
        List<Experience> expList = new ArrayList<>();
        JsonNode expNode = root.path("experience");
        if (expNode.isArray()) {
            for (JsonNode e : expNode) {
                String type = normalizeExperienceType(text(e, "type"));
                expList.add(new Experience(
                        UUID.randomUUID().toString(),
                        fallbackExperienceTitle(text(e, "title"), type), text(e, "company"),
                        text(e, "dates"), text(e, "description"), type));
            }
        }
        target.setExperience(expList);
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText().trim();
    }

    /** Keeps only the two recognized tag values — anything else the model invents collapses to "". */
    private String normalizeExperienceType(String type) {
        if ("Internship".equalsIgnoreCase(type)) return "Internship";
        if ("Training".equalsIgnoreCase(type)) return "Training";
        return "";
    }

    /**
     * Guarantees an experience entry never ends up with a blank title, even if the model
     * ignored the instruction to synthesize one from context — an Internship/Training entry
     * with no title at all reads as a broken row on the resume, so it falls back to the type
     * itself rather than staying empty.
     */
    private String fallbackExperienceTitle(String title, String type) {
        if (!title.isBlank()) return title;
        return type.isBlank() ? "" : type;
    }

    /**
     * Builds the project's displayed summary from the model's structured output.
     *
     * <p>The prompt asks for a "summaryBullets" JSON array — a shape small models handle far
     * more reliably than a single string containing embedded newlines (llama3.2:3b was
     * observed both crushing multi-line content onto one line and, worse, emitting invalid
     * JSON when it tried to represent bullets as an array under the old "summary" string
     * field). If the model still answers with a plain "summary" string anyway, fall back to
     * normalizing whatever bullet/line markers it used rather than losing the content.
     */
    private String extractProjectSummary(JsonNode projectNode) {
        JsonNode bulletsNode = projectNode.path("summaryBullets");
        if (bulletsNode.isArray()) {
            List<String> facts = new ArrayList<>();
            bulletsNode.forEach(n -> {
                String fact = n.asText().trim();
                if (!fact.isEmpty()) facts.add(fact);
            });
            return joinAsBullets(facts);
        }
        String fallback = text(projectNode, "summary");
        return fallback.isEmpty() ? "" : normalizeBullets(fallback);
    }

    /** Joins plain-text facts into one "• " bullet per line, stripping any marker the model added anyway. */
    private String joinAsBullets(List<String> facts) {
        List<String> bullets = new ArrayList<>();
        for (String fact : facts) {
            String cleaned = fact.replaceFirst("^[•*\\-]\\s*", "").trim();
            if (!cleaned.isEmpty()) {
                bullets.add("• " + cleaned);
            }
        }
        return String.join("\n", bullets);
    }

    /**
     * Normalizes a free-text bulleted summary into one "• " line per fact — used only as a
     * fallback for the rare case where the model ignores the "summaryBullets" array schema
     * and answers with a single string instead.
     */
    private String normalizeBullets(String summary) {
        String trimmed = summary.trim();
        if (trimmed.isEmpty()) return "";

        List<String> bullets = new ArrayList<>();
        for (String part : trimmed.split("[•\\r\\n]+")) {
            String cleaned = part.replaceFirst("^[-*]\\s*", "").trim();
            if (!cleaned.isEmpty()) {
                bullets.add("• " + cleaned);
            }
        }
        return String.join("\n", bullets);
    }

    // -------------------------------------------------------------------------
    // Retry layers
    // -------------------------------------------------------------------------

    /**
     * Schema validity retry — malformed/non-parseable JSON → retry.
     * Capped at one retry to maintain bounded latency.
     */
    private ResumeData extractWithSchemaRetry(String rawText) throws Exception {
        try {
            String json = callOllama(rawText);
            log.debug("Ollama response: {}", json);
            ResumeData result = new ResumeData();
            mapJsonToBean(json, result);
            return result;
        } catch (Exception ex) {
            log.warn("First extraction failed with schema error, retrying: {}", ex.getMessage());
            String json = callOllama(rawText);
            log.debug("Ollama retry response: {}", json);
            ResumeData result = new ResumeData();
            mapJsonToBean(json, result);
            return result;
        }
    }

    /**
     * Content quality retry — valid JSON, but thin content → retry with specific feedback.
     * Capped at one retry to maintain bounded latency.
     *
     * <p>The retry call re-extracts the whole resume, not just the flagged entries — so its
     * result is merged back in rather than replacing {@code currentResult} wholesale. Without
     * that merge, a single thin project (e.g. "Weather app.") would trigger a retry whose fresh
     * regeneration of an already-good, unrelated project (e.g. a fully-detailed one) could come
     * back worse or empty, silently destroying data that was already correct.
     */
    private ResumeData extractWithQualityRetry(String rawText, ResumeData currentResult) {
        QualityIssues issues = validateContentQuality(currentResult);
        if (issues.messages().isEmpty()) {
            return currentResult;
        }
        log.warn("Content quality issues detected, retrying with feedback: {}", issues.messages());
        try {
            String prompt = buildPromptWithFeedback(rawText, issues.messages());
            String json = callOllamaWithPrompt(prompt);
            log.debug("Ollama quality retry response: {}", json);
            ResumeData retryResult = new ResumeData();
            mapJsonToBean(json, retryResult);
            return mergeFlaggedEntries(currentResult, retryResult, issues);
        } catch (Exception ex) {
            // The retry is a best-effort improvement, not a requirement — if it comes back
            // malformed, keep the already-valid first-pass extraction rather than losing it.
            log.warn("Quality retry produced unusable output, keeping original extraction: {}", ex.getMessage());
            return currentResult;
        }
    }

    /**
     * Applies the retry's output only to the specific project/experience entries that were
     * flagged as thin or placeholder-copied, leaving every other entry exactly as first
     * extracted. Falls back to keeping the original entry if the retry's replacement for it
     * is itself blank or still a placeholder copy.
     */
    private ResumeData mergeFlaggedEntries(ResumeData original, ResumeData retry, QualityIssues issues) {
        List<Project> projects = original.getProjects();
        List<Project> retryProjects = retry.getProjects();
        for (int i : issues.projectIndexes()) {
            if (i < retryProjects.size()) {
                String candidate = retryProjects.get(i).getSummary();
                if (candidate != null && !candidate.isBlank() && !isPlaceholderCopy(candidate)) {
                    projects.get(i).setSummary(candidate);
                }
            }
        }

        List<Experience> experience = original.getExperience();
        List<Experience> retryExperience = retry.getExperience();
        for (int i : issues.experienceIndexes()) {
            if (i < retryExperience.size()) {
                String candidate = retryExperience.get(i).getDescription();
                if (candidate != null && !candidate.isBlank()) {
                    experience.get(i).setDescription(candidate);
                }
            }
        }
        return original;
    }

    private String callOllamaWithPrompt(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model",  ollamaModel,
                "prompt", prompt,
                "stream", false
        );

        String url      = ollamaBaseUrl + "/api/generate";
        String response = restTemplate.postForObject(url, body, String.class);

        JsonNode root = objectMapper.readTree(response);
        return root.path("response").asText();
    }

    /**
     * Content-quality issues found in an extraction, keyed by entry index so a retry's
     * result can be merged back in per-entry instead of wholesale (see
     * {@link #mergeFlaggedEntries}).
     */
    private record QualityIssues(List<String> messages, Set<Integer> projectIndexes, Set<Integer> experienceIndexes) {}

    /**
     * Validates content quality of extracted resume data.
     * Returns the specific issues to feed back to the LLM, tagged with which entries they concern.
     */
    private QualityIssues validateContentQuality(ResumeData data) {
        List<String> issues = new ArrayList<>();
        Set<Integer> projectIndexes = new HashSet<>();
        Set<Integer> experienceIndexes = new HashSet<>();

        List<Project> projects = data.getProjects();
        for (int i = 0; i < projects.size(); i++) {
            Project p = projects.get(i);
            if (isPlaceholderCopy(p.getSummary())) {
                issues.add("The summary for project \"" + p.getTitle() + "\" is just the format " +
                        "illustration copied verbatim, not real content. Fill \"summaryBullets\" " +
                        "with 2-4 real facts (as separate array strings) based only on the resume " +
                        "text, or an empty array if there isn't enough information about this project.");
                projectIndexes.add(i);
                continue;
            }
            int words = p.getSummary() == null ? 0 : p.getSummary().trim().split("\\s+").length;
            if (words > 0 && words < 12) {
                issues.add("The summary for project \"" + p.getTitle() + "\" is only " + words +
                        " words (\"" + p.getSummary() + "\"). Expand \"summaryBullets\" to 2-4 real " +
                        "facts (as separate array strings) covering what was built, the tech used, " +
                        "and any measurable outcome.");
                projectIndexes.add(i);
            }
        }

        List<Experience> experience = data.getExperience();
        for (int i = 0; i < experience.size(); i++) {
            Experience e = experience.get(i);
            int words = e.getDescription() == null ? 0 : e.getDescription().trim().split("\\s+").length;
            if (words < 8) {
                issues.add("The description for \"" + e.getTitle() + "\" at \"" + e.getCompany() +
                        "\" is too thin — expand using detail actually present in the source text.");
                experienceIndexes.add(i);
            }
        }
        return new QualityIssues(issues, projectIndexes, experienceIndexes);
    }

    /**
     * @return true if {@code summary} is (or clearly contains) the format-illustration
     * text verbatim rather than content the model actually extracted from the resume.
     */
    private boolean isPlaceholderCopy(String summary) {
        return summary != null && summary.toLowerCase().contains(PLACEHOLDER_COPY_MARKER);
    }

    /**
     * Clears any project summary that still matches the format-illustration example after
     * retries are exhausted, so the student never sees fabricated content passed off as their own.
     */
    private void sanitizePlaceholderCopies(ResumeData data) {
        for (Project p : data.getProjects()) {
            if (isPlaceholderCopy(p.getSummary())) {
                log.warn("Project \"{}\" summary still matched the format illustration after retries; clearing it.",
                        p.getTitle());
                p.setSummary("");
            }
        }
    }

    /**
     * Drops any experience entry whose title matches an already-extracted project — the model
     * occasionally lists a "Projects" section entry under "experience" too despite explicit
     * instructions not to. Titles are compared on the part before a "-"/"|" separator so
     * "Truxlo" and "Truxlo - Mobile Logistics Platform" are recognized as the same thing.
     */
    private void removeExperienceEntriesDuplicatingProjects(ResumeData data) {
        Set<String> projectTitles = data.getProjects().stream()
                .map(p -> normalizeTitleForComparison(p.getTitle()))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toSet());

        data.getExperience().removeIf(e -> {
            boolean duplicate = projectTitles.contains(normalizeTitleForComparison(e.getTitle()));
            if (duplicate) {
                log.warn("Experience entry \"{}\" duplicates a project; removing it.", e.getTitle());
            }
            return duplicate;
        });
    }

    private String normalizeTitleForComparison(String title) {
        if (title == null) return "";
        return title.split("[-|]")[0].toLowerCase().trim();
    }

    /**
     * Copies data from one ResumeData to another.
     */
    private void copyResumeData(ResumeData source, ResumeData target) {
        target.setFullName(source.getFullName());
        target.setEmail(source.getEmail());
        target.setPhone(source.getPhone());
        target.setLinkedinUrl(source.getLinkedinUrl());
        target.setGithubUrl(source.getGithubUrl());
        target.setSkills(new ArrayList<>(source.getSkills()));
        target.setEducation(new ArrayList<>(source.getEducation()));
        target.setProjects(new ArrayList<>(source.getProjects()));
        target.setExperience(new ArrayList<>(source.getExperience()));
    }
}
