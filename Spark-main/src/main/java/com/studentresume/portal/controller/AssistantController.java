package com.studentresume.portal.controller;

import com.studentresume.portal.model.ResumeData;
import com.studentresume.portal.service.AssistantService;
import com.studentresume.portal.service.AtsImprovementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stateless AI writing assistant endpoint, plus the deterministic ATS-improvement scan.
 *
 * <p>Accepts {@code sectionText} + {@code instruction}, returns a JSON suggestion.
 * The frontend displays Accept / Reject buttons; acceptance patches the field via htmx.
 * The suggestion is never auto-applied.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;
    private final AtsImprovementService atsImprovementService;
    private final ResumeData resumeData;

    public AssistantController(AssistantService assistantService,
                                AtsImprovementService atsImprovementService,
                                ResumeData resumeData) {
        this.assistantService = assistantService;
        this.atsImprovementService = atsImprovementService;
        this.resumeData = resumeData;
    }

    /**
     * @param sectionText current content of the resume field being improved, or (when
     *                    {@code scope=resume}) a full-resume context blob
     * @param instruction user-selected chip text (e.g. "Make it punchier") or free-typed text
     * @param scope       {@code "field"} (default) rewrites {@code sectionText} directly;
     *                    {@code "resume"} treats it as whole-resume context and returns
     *                    advisory feedback instead of a drop-in replacement
     * @return JSON: {@code { "suggestion": "..." }}
     */
    @PostMapping("/suggest")
    public ResponseEntity<Map<String, String>> suggest(
            @RequestParam String sectionText,
            @RequestParam String instruction,
            @RequestParam(defaultValue = "field") String scope) {

        String suggestion = "resume".equals(scope)
                ? assistantService.advise(sectionText, instruction)
                : assistantService.suggest(sectionText, instruction);

        if (suggestion.isBlank()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "AI assistant is unavailable. Please try again."));
        }

        return ResponseEntity.ok(Map.of("suggestion", suggestion));
    }

    /**
     * Fast, synchronous, no-AI scan of the current resume for field-level ATS improvements.
     * The frontend takes each non-skills target and separately calls {@link #suggest} to get
     * the actual rewrite — this endpoint never calls Ollama.
     *
     * @return JSON: {@code { "targets": [ ImprovementTarget, ... ] } } — an empty list is a
     *         normal, non-error outcome (resume already looks solid, or is empty).
     */
    @GetMapping("/ats-improve/targets")
    public ResponseEntity<Map<String, Object>> atsImproveTargets() {
        return ResponseEntity.ok(Map.of("targets", atsImprovementService.identifyTargets(resumeData)));
    }
}
