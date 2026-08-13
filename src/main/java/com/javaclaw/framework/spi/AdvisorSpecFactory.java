package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.CapabilityId;
import org.springframework.ai.chat.client.advisor.api.Advisor;

public interface AdvisorSpecFactory {
    CapabilityId capabilityId();

    /** Stable identifier used to reconnect a persisted AdvisorSpec to this exact artifact. */
    String advisorId();

    AdvisorSpec create(JsonNode capabilityConfiguration, CompilationContext context);

    /** Creates run-scoped behavior; factories must not retain the runtime context. */
    Advisor createAdvisor(AdvisorSpec specification, AdvisorRuntimeContext context);
}
