package com.javaclaw.platform.spring;

import com.javaclaw.framework.core.AgentEngine;
import com.javaclaw.framework.core.ReasoningGateway;
import com.javaclaw.framework.core.ToolInvocationGateway;
import com.javaclaw.framework.spi.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

import java.util.Objects;

/** Fails startup when composition accidentally creates a second framework runtime boundary. */
final class FrameworkStartupDiagnostics implements SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(FrameworkStartupDiagnostics.class);
    private final ApplicationContext context;

    FrameworkStartupDiagnostics(ApplicationContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void afterSingletonsInstantiated() {
        requireExactlyOne(AgentEngine.class);
        requireExactlyOne(RunStore.class);
        requireExactlyOne(ReasoningGateway.class);
        requireExactlyOne(ToolInvocationGateway.class);
        requireExactlyOne(io.micrometer.observation.ObservationRegistry.class);
        var reasoning = context.getBean(ReasoningGateway.class);
        if (!(reasoning instanceof com.javaclaw.framework.springai.SpringAiReasoningGateway springAi)
                || springAi.toolCallingAdvisorCountPerPlan() != 1) {
            throw new IllegalStateException(
                    "each ExecutionPlan requires exactly one Spring AI ToolCallingAdvisor");
        }
        log.info("Agent Framework 启动诊断通过：单 AgentEngine/RunStore/ObservationRegistry，"
                + "每个 ExecutionPlan 单 ToolCallingAdvisor");
    }

    private void requireExactlyOne(Class<?> type) {
        int count = context.getBeansOfType(type).size();
        if (count != 1) {
            throw new IllegalStateException("framework requires exactly one "
                    + type.getSimpleName() + " bean, found " + count);
        }
    }
}
