package com.javaclaw.framework.springai;

import com.javaclaw.framework.spi.AdvisorSpec;
import com.javaclaw.framework.spi.AdvisorSpecFactory;
import com.javaclaw.framework.spi.AdvisorRuntimeContext;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps provider-neutral extension declarations to Spring AI Advisors. */
public final class SpringAiAdvisorRegistry {
    public List<Advisor> create(
            List<AdvisorSpec> specs,
            List<AdvisorSpecFactory> factories,
            AdvisorRuntimeContext context) {
        Map<String, AdvisorSpecFactory> byId = new HashMap<>();
        for (AdvisorSpecFactory factory : factories) {
            if (byId.putIfAbsent(factory.advisorId(), factory) != null) {
                throw new IllegalStateException("duplicate advisor factory: " + factory.advisorId());
            }
        }
        List<Advisor> result = new ArrayList<>();
        for (AdvisorSpec spec : specs) {
            AdvisorSpecFactory factory = byId.get(spec.id());
            if (factory == null) throw new IllegalStateException(
                    "advisor factory not locked in execution plan: " + spec.id());
            Advisor advisor = Objects.requireNonNull(
                    factory.createAdvisor(spec, context), "advisor");
            if (advisor instanceof ToolCallingAdvisor) {
                throw new IllegalStateException("extensions cannot create a second ToolCallingAdvisor");
            }
            result.add(advisor);
        }
        return List.copyOf(result);
    }
}
