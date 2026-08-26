package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.spi.AgentStudioUiSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentStudioAuthoringConstraintsTest {
    @Test
    void validatesNestedObjectsAndArraysWithoutNarrowingTheCompatibilitySchema() {
        var schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        var nested = schema.putObject("properties").putObject("nested");
        nested.put("type", "object");
        var items = nested.putObject("properties").putObject("values");
        items.put("type", "array");
        var item = items.putObject("items");
        item.put("type", "integer");
        item.put("maximum", 50);
        item.put(AgentStudioUiSchema.AUTHORING_MAXIMUM, 3);

        var value = JsonNodeFactory.instance.objectNode();
        value.putObject("nested").putArray("values").add(2).add(8);

        var issues = AgentStudioAuthoringConstraints.validate(schema, value, "/configuration");
        assertEquals(1, issues.size());
        assertEquals("/configuration/nested/values/1", issues.getFirst().path());
        assertEquals("configuration.authoring_maximum", issues.getFirst().code());
    }
}
