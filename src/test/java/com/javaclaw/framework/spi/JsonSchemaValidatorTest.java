package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    @Test
    void validatesDraft202012DependentRequiredAndUnevaluatedProperties() throws Exception {
        var schema = json.readTree("""
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "properties": {
                    "card": {"type": "string"},
                    "billingAddress": {"type": "string"}
                  },
                  "dependentRequired": {"card": ["billingAddress"]},
                  "unevaluatedProperties": false
                }
                """);

        var missingDependency = JsonNodeFactory.instance.objectNode().put("card", "1234");
        assertFalse(validator.validate(schema, missingDependency, "/configuration").isEmpty());

        var valid = JsonNodeFactory.instance.objectNode()
                .put("card", "1234").put("billingAddress", "Shanghai");
        assertTrue(validator.validate(schema, valid, "/configuration").isEmpty());

        var unknown = valid.deepCopy().put("legacy", true);
        assertFalse(validator.validate(schema, unknown, "/configuration").isEmpty());
    }

    @Test
    void rejectsSchemaThatDoesNotConformToDraft202012MetaSchema() throws Exception {
        var invalid = json.readTree("{\"type\":\"unknown\"}");

        assertThrows(IllegalArgumentException.class,
                () -> validator.requireValidSchema(invalid, "test schema"));
    }
}
