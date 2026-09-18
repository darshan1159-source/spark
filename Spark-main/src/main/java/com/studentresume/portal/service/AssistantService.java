package com.studentresume.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wraps Ollama to provide contextual resume-writing suggestions.
 *
 * <p>One stateless endpoint: receives the current section text and a user instruction,
 * returns a rewrite suggestion. The student must explicitly accept or reject it — it is
 * never auto-applied.
 *
 * <p>Reuses the same Ollama model as {@link CvParsingService}.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String       ollamaBaseUrl;
    private final String       ollamaModel;

    public AssistantService(RestTemplate restTemplate,
                            @Value("${ollama.base-url}") String ollamaBaseUrl,
                            @Value("${ollama.model}")    String ollamaModel) {
        this.restTemplate  = restTemplate;
        this.objectMapper  = new ObjectMapper();
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaModel   = ollamaModel;
    }

    /**
     * Produces a rewrite suggestion for the given {@code sectionText} following {@code instruction}.
     *
     * @param sectionText current content of the field being improved
     * @param instruction user-selected chip text or free-typed instruction
     * @return suggested rewrite (may be empty string on failure)
     */
    public String suggest(String sectionText, String instruction) {
        try {
            String prompt = buildPrompt(sectionText, instruction);
            String result = callOllama(prompt);
            // Small models reliably enforce this themselves only when told to; if the field
            // being edited was already bulleted, guarantee the rewrite still is too, regardless
            // of whether the model actually followed that instruction.
            if (looksBulleted(sectionText)) {
                result = normalizeBullets(result);
            }
            return result;
        } catch (Exception ex) {
            log.error("Writing assistant failed: {}", ex.getMessage(), ex);
            return "";
        }
    }

    /**
     * Produces whole-resume feedback for {@code instruction} — critique, not a rewrite.
     * Unlike {@link #suggest}, there is no single field this could be written back into, so
     * the response is meant to be read, not accepted.
     *
     * @param resumeContext a summary of the whole resume (all sections, current field values)
     * @param instruction   user-selected chip text (e.g. "What's missing?") or free-typed question
     * @return advisory feedback (may be empty string on failure)
     */
    public String advise(String resumeContext, String instruction) {
        try {
            String prompt = buildAdvicePrompt(resumeContext, instruction);
            return callOllama(prompt);
        } catch (Exception ex) {
            log.error("Writing assistant (advice) failed: {}", ex.getMessage(), ex);
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private String buildPrompt(String sectionText, String instruction) {
        String formatNote = looksBulleted(sectionText)
                ? "\nThe text below is a bulleted list — each fact on its own line, starting " +
                  "with \"• \". Your rewrite MUST keep that exact format: one fact per line, " +
                  "each line starting with \"• \", separated by real line breaks. Do not merge " +
                  "the bullets into a paragraph.\n"
                : "";
        return """
                You are a professional resume writing assistant. \
                Rewrite the following resume text according to the instruction provided. \
                Return ONLY the rewritten text — no preamble, no explanation, no quotation marks. \
                Only rewrite what's already there — do not add a job title, role label, heading, \
                or any other content that isn't a direct rewrite of the existing text.
                %s
                Instruction: %s

                Text to improve:
                %s
                """.formatted(formatNote, instruction, sectionText);
    }

    private String buildAdvicePrompt(String resumeContext, String instruction) {
        return """
                You are a resume reviewer giving direct, specific feedback. Do NOT rewrite the \
                resume or any part of it — only answer the question below with concrete, \
                actionable observations about the resume as a whole. Reference specific \
                sections (Education, Skills, Projects, Experience) by name where relevant. \
                Keep it to a few short sentences or a short bulleted list — no preamble.

                Question: %s

                Resume:
                ---
                %s
                ---
                """.formatted(instruction, resumeContext);
    }

    private boolean looksBulleted(String text) {
        return text != null && (text.contains("•") || text.trim().split("\\r?\\n").length > 1);
    }

    /**
     * Rebuilds {@code text} as one "• " line per fact, regardless of whatever bullet/line
     * markers (or lack of them) the model actually produced. Mirrors the same normalization
     * used for CV-parsed project summaries — the model reliably marks facts with "•" even
     * when it doesn't reliably put each one on its own real line.
     */
    private String normalizeBullets(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
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

    private String callOllama(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model",  ollamaModel,
                "prompt", prompt,
                "stream", false
        );

        String url      = ollamaBaseUrl + "/api/generate";
        String response = restTemplate.postForObject(url, body, String.class);

        JsonNode root = objectMapper.readTree(response);
        return root.path("response").asText().trim();
    }
}
