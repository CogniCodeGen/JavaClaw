package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Provider-neutral declaration turned into a Spring AI Advisor only by framework.springai. */
public record AdvisorSpec(String id, String slot, int order, JsonNode configuration) {
    public AdvisorSpec {
        id = Objects.requireNonNull(id, "id");
        slot = Objects.requireNonNull(slot, "slot");
        configuration = Objects.requireNonNull(configuration, "configuration").deepCopy();
    }

    @Override public JsonNode configuration() { return configuration.deepCopy(); }
}
