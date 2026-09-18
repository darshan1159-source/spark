package com.studentresume.portal.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** One work experience entry. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Experience {
    /** Stable identity — see {@link Education#getId()} for why this exists. */
    private String id = UUID.randomUUID().toString();

    private String title       = "";
    private String company     = "";
    private String dates       = "";
    private String description = "";

    /** "" (regular experience) | "Internship" | "Training" — shown as an explicit tag on the resume. */
    private String type = "";
}
