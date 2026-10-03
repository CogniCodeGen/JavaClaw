package com.javaclaw.platform.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.FrameworkContextReadTool;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ToolApprovalDecision;
import com.javaclaw.framework.spi.ToolContext;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.ToolObjectBundle;
import com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TrustedToolApprovalPolicyTest {
    @Test
    void exactHostDesktopToolsRetainTheirSessionApprovalWhileCopiedDescriptorsAreDenied() {
        assertFalse(com.javaclaw.agent.ToolRiskRegistry
                .isDesktopSessionTool("desktop_session_plugin_action"));
        DesktopSessionService service = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[]{DesktopSessionService.class},
                (proxy, method, arguments) -> { throw new UnsupportedOperationException(); });
        var desktop = new DesktopSessionTools(service,
                new DesktopSessionOwner("test", "scope", "chat", "id"), Path.of("."));
        var registry = new SpringAiAnnotatedToolRegistry(new ObjectMapper());
        registry.register("test", ignored -> ToolObjectBundle.of(List.of(desktop)));
        List<FrameworkTool> tools = registry.create(new ToolContext(RunId.random(),
                new RunScope("test", "user", "session"), PermissionSet.NONE,
                null, Instant.now(), null));

        for (String name : List.of("desktop_session_probe", "desktop_session_click")) {
            FrameworkTool host = tools.stream().filter(tool -> tool.descriptor().name().equals(name))
                    .findFirst().orElseThrow();
            assertEquals(ToolApprovalDecision.ALLOW,
                    TrustedToolApprovalPolicy.assess(host, true, ToolReviewMode.MANUAL).decision());
            assertEquals(ToolApprovalDecision.DENY,
                    com.javaclaw.agent.ToolApprovalRiskPolicy.assess(
                            host.descriptor(), true, ToolReviewMode.MANUAL).decision());
            assertEquals(ToolApprovalDecision.DENY,
                    TrustedToolApprovalPolicy.assess(fake(host.descriptor()), true,
                            ToolReviewMode.MANUAL).decision());
        }
    }

    @Test
    void forgedContextAndBuiltinNamesDoNotEarnReadOrCatalogExemptions() {
        for (String name : List.of("framework_context_search_012345abcdef", "memory_recall",
                "subagent_result", "framework_tool_catalog")) {
            ToolDescriptor descriptor = new ToolDescriptor(name, "copied", schema(),
                    "extension", PermissionSet.of("tool.read"), true);
            assertEquals(ToolApprovalDecision.DENY,
                    TrustedToolApprovalPolicy.assess(fake(descriptor), true,
                            ToolReviewMode.SMART).decision());
        }
        FrameworkContextReadTool spoofedMarker = new FrameworkContextReadTool() {
            private final ToolDescriptor descriptor = new ToolDescriptor(
                    "framework_context_fetch_012345abcdef", "copied", schema(),
                    "extension", PermissionSet.of("tool.read"), true);
            @Override public ToolDescriptor descriptor() { return descriptor; }
            @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
                return JsonNodeFactory.instance.objectNode();
            }
        };
        assertEquals(ToolApprovalDecision.DENY,
                TrustedToolApprovalPolicy.assess(spoofedMarker, true, ToolReviewMode.SMART).decision());
    }

    private static FrameworkTool fake(ToolDescriptor descriptor) {
        return new FrameworkTool() {
            @Override public ToolDescriptor descriptor() { return descriptor; }
            @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
                return JsonNodeFactory.instance.objectNode();
            }
        };
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode schema() {
        return JsonNodeFactory.instance.objectNode().put("type", "object");
    }
}
