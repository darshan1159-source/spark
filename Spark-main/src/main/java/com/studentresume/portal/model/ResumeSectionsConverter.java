package com.studentresume.portal.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * (De)serializes {@link ResumeSections} to/from one JSON TEXT column. Applied via explicit
 * {@code @Convert(converter = ResumeSectionsConverter.class)} on the one field that needs it,
 * not {@code @Converter(autoApply = true)} — this codebase avoids implicit action-at-a-distance.
 *
 * <p>Constructs its own {@link ObjectMapper} rather than injecting the Spring-managed one,
 * matching {@code CvParsingService}'s existing precedent.
 */
@Converter
public class ResumeSectionsConverter implements AttributeConverter<ResumeSections, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(ResumeSections sections) {
        try {
            return MAPPER.writeValueAsString(sections == null ? ResumeSections.empty() : sections);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize resume sections", e);
        }
    }

    @Override
    public ResumeSections convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return ResumeSections.empty();
        try {
            return MAPPER.readValue(json, ResumeSections.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize resume sections", e);
        }
    }
}
