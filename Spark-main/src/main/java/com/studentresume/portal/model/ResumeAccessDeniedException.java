package com.studentresume.portal.model;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a resume lookup fails its ownership check. Maps to 404, not 403 — a 403 would
 * confirm to an attacker that the resume ID exists and belongs to someone else, which is
 * exactly the information disclosure this check exists to prevent. {@code @ResponseStatus}
 * means Spring MVC translates this automatically; no {@code @ControllerAdvice} needed.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResumeAccessDeniedException extends RuntimeException {
    public ResumeAccessDeniedException(String message) {
        super(message);
    }
}
