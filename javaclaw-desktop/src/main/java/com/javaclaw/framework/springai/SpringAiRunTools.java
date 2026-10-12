package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.ToolGroupAccess;
import com.javaclaw.framework.core.TrustedCapabilityRegistry;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.javaclaw.framework.springai.ReasoningGatewaySupport.closeTools;

/** 按 Run 权限装配工具，并在交给模型前验证宿主工具身份。 */
final class SpringAiRunTools {
    private SpringAiRunTools() { }

    static List<FrameworkTool> create(ReasoningRequest request, TrustedCapabilityRegistry capabilities) {
        JsonNode disabled = request.runRequest().attributes().get("framework.disableTools");
        if (disabled != null && disabled.asBoolean(false)) return List.of();
        ToolContext context = new ToolContext(
                request.runId(), request.runRequest().scope(),
                request.plan().descriptor().permissions(), request.control(),
                request.control().deadline(), request.runRequest());
        List<FrameworkTool> runTools = new ArrayList<>();
        try {
            for (var factory : request.plan().toolFactories()) {
                runTools.add(Objects.requireNonNull(factory.create(context), "framework tool"));
            }
            for (var provider : request.plan().toolProviderFactories()) {
                List<FrameworkTool> provided = Objects.requireNonNull(
                        provider.create(context), "framework tools");
                provided.forEach(tool -> runTools.add(Objects.requireNonNull(tool, "framework tool")));
            }
            for (int index = runTools.size() - 1; index >= 0; index--) {
                FrameworkTool tool = runTools.get(index);
                boolean groupAllowed = ToolGroupAccess.allows(
                        request.runRequest(), tool.descriptor().group());
                boolean permissionAllowed = request.plan().descriptor().permissions()
                        .containsAll(tool.descriptor().requiredPermissions());
                if (groupAllowed && permissionAllowed) continue;
                runTools.remove(index);
                try {
                    tool.close();
                } catch (Exception failure) {
                    throw new IllegalStateException("cannot close filtered tool", failure);
                }
            }
            bindTrustedCapabilities(runTools, capabilities);
        } catch (RuntimeException failure) {
            try {
                closeTools(runTools);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return runTools;
    }

    /** 扩展可以复制描述字段，因此只有宿主实现与可信回执适配器能够绑定可信能力。 */
    static void bindTrustedCapabilities(
            List<FrameworkTool> runTools, TrustedCapabilityRegistry capabilities) {
        for (FrameworkTool tool : runTools) {
            String name = tool.descriptor().name();
            if (com.javaclaw.agent.ToolRiskRegistry.isKnownHostTool(name)
                    && !SpringAiAnnotatedToolRegistry.isExactHostTool(tool)) {
                throw new IllegalStateException("untrusted tool impersonates host tool: "
                        + name);
            }
            if (!capabilities.hasTrustedTool(name)) continue;
            if (!SpringAiAnnotatedToolRegistry.isTrustedReceiptSource(tool)) {
                throw new IllegalStateException("tool has no trusted receipt adapter: " + name);
            }
            capabilities.bindHostTool(tool.descriptor());
        }
    }

}
