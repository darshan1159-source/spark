package com.studentresume.portal.model;

import java.util.List;

/**
 * Pure wrapper for JSON (de)serialization of a resume's nested lists into one DB column —
 * reuses the existing {@link Education}/{@link Project}/{@link Experience} classes directly
 * rather than introducing a parallel data model.
 */
public record ResumeSections(
        List<Education> education,
        List<Project> projects,
        List<Experience> experience,
        List<String> skills
) {
    public static ResumeSections empty() {
        return new ResumeSections(List.of(), List.of(), List.of(), List.of());
    }
}
