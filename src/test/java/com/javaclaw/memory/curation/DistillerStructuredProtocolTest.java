package com.javaclaw.memory.curation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistillerStructuredProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();

    @Test
    void emptyArraysRepresentNoFactAndNoSupersession() throws Exception {
        assertTrue(SCHEMAS.validate(Distiller.extractionSchema(),
                JSON.readTree("{\"facts\":[],\"entities\":[],\"preferenceClaims\":[]}"), "/output").isEmpty());
        assertTrue(SCHEMAS.validate(Distiller.indexSchema(3),
                JSON.readTree("{\"indexes\":[]}"), "/output").isEmpty());
    }

    @Test
    void localizedSentinelsCannotBeControlResults() throws Exception {
        assertFalse(SCHEMAS.validate(Distiller.extractionSchema(),
                JSON.readTree("\"无\""), "/output").isEmpty());
        assertFalse(SCHEMAS.validate(Distiller.extractionSchema(),
                JSON.readTree("{\"facts\":[],\"entities\":[],\"preferenceClaims\":\"我偏好简洁\"}"),
                "/output").isEmpty());
        assertFalse(SCHEMAS.validate(Distiller.indexSchema(3),
                JSON.readTree("\"1,3\""), "/output").isEmpty());
    }
}
