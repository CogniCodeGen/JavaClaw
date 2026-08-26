package com.javaclaw.framework.builtin.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.spi.AdvisorRuntimeContext;
import com.javaclaw.framework.spi.AdvisorSpec;
import com.javaclaw.framework.spi.AdvisorSpecFactory;
import com.javaclaw.framework.spi.CompilationContext;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.time.Clock;
import java.util.Objects;

/** Compiles the formerly declarative context.compaction capability into a real Advisor. */
public final class ContextCompactionAdvisorFactory implements AdvisorSpecFactory {
    public static final String ADVISOR_ID = "context.compaction.advisor";
    private final ConversationContextSummaryStore store;
    private final ConversationHistorySource historySource;
    private final Clock clock;

    public ContextCompactionAdvisorFactory(ConversationContextSummaryStore store, Clock clock) {
        this(store, (workspace, session, lastMessageId) -> java.util.Optional.empty(), clock);
    }

    public ContextCompactionAdvisorFactory(
            ConversationContextSummaryStore store,
            ConversationHistorySource historySource,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.historySource = Objects.requireNonNull(historySource, "historySource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public CapabilityId capabilityId() {
        return new CapabilityId("context.compaction");
    }

    @Override public String advisorId() { return ADVISOR_ID; }

    @Override
    public AdvisorSpec create(JsonNode capabilityConfiguration, CompilationContext context) {
        return new AdvisorSpec(ADVISOR_ID, "context", 50, capabilityConfiguration);
    }

    @Override
    public Advisor createAdvisor(AdvisorSpec specification, AdvisorRuntimeContext context) {
        return new ContextCompactionAdvisor(
                specification.order(), context, store, historySource, clock);
    }
}
