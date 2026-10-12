package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.spi.EffectReceiptV1;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionExecutionPolicyTest {
    @Test
    void 交互能力只匹配完整前缀且空值不授权() {
        assertTrue(InteractionExecutionPolicy.isInteractionCriterion("browser.observe"));
        assertTrue(InteractionExecutionPolicy.isInteractionCriterion("desktop.launch"));
        for (String capability : List.of("browser", "desktop", "browserish.observe", "file.read", "")) {
            assertFalse(InteractionExecutionPolicy.isInteractionCriterion(capability), capability);
        }
        assertFalse(InteractionExecutionPolicy.isInteractionCriterion(null));
    }

    @Test
    void 交互条件筛选保留冻结顺序且缺少合同时为空() {
        var file = criterion("file", "file.read");
        var browser = criterion("browser", "browser.observe");
        var desktop = criterion("desktop", "desktop.observe");
        var contract = new TaskContractV3(3, "混合任务", List.of(file, browser, desktop), true, true, "definition");

        assertEquals(List.of(browser, desktop), InteractionExecutionPolicy.interactionCriteria(contract));
        assertEquals(List.of(), InteractionExecutionPolicy.interactionCriteria(null));
        assertEquals(List.of(), InteractionExecutionPolicy.interactionCriteria(
                new TaskContractV3(3, "纯回答", List.of(), false, true, "model")));
    }

    @Test
    void 通用执行门禁保留委派恢复入口而不承担目录合同筛选() {
        var compiled = JsonNodeFactory.instance.objectNode();
        compiled.putObject("interaction.run");
        var request = RunRequest.builder().agent(AgentDefinitionRef.latest("agent"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("纯回答"))
                .attributes(Map.of("framework.compiledCapabilities", compiled)).build();

        assertTrue(InteractionExecutionPolicy.isMain(request));
        assertTrue(InteractionExecutionPolicy.allowsTool(request, List.of(),
                InteractionExecutionPolicy.DELEGATE_TOOL, InteractionExecutionPolicy.TOOL_GROUP));
    }

    @Test
    void 工具身份只认宿主登记且不得跨后端执行() {
        var modes = JsonNodeFactory.instance.arrayNode().add("DESKTOP").add("BROWSER");
        var request = RunRequest.builder().agent(AgentDefinitionRef.latest(InteractionExecutionPolicy.AGENT_ID))
                .profile(RunProfileRef.latest(InteractionExecutionPolicy.PROFILE_ID))
                .source(new InvocationSource(InteractionExecutionPolicy.SOURCE_TYPE, "interaction-executor"))
                .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("查看界面"))
                .attributes(Map.of(InteractionExecutionPolicy.ALLOWED_MODES_ATTRIBUTE, modes,
                        InteractionExecutionPolicy.INITIAL_MODE_ATTRIBUTE, JsonNodeFactory.instance.textNode("DESKTOP")))
                .build();
        assertTrue(InteractionExecutionPolicy.allowsTool(request, List.of(), "desktop_session_observe", "desktop-session"));
        for (String name : List.of("desktop_session_forged", "web_forged", "site_forged", "web_navigate")) {
            assertFalse(InteractionExecutionPolicy.allowsTool(request, List.of(), name, "desktop-session"), name);
        }
        assertFalse(InteractionExecutionPolicy.allowsTool(request, List.of(), "desktop_session_click", "web"));
        var browser = request.withAttribute(InteractionExecutionPolicy.INITIAL_MODE_ATTRIBUTE,
                JsonNodeFactory.instance.textNode("BROWSER"));
        assertTrue(InteractionExecutionPolicy.allowsTool(browser, List.of(), "web_navigate", "web"));
        assertTrue(InteractionExecutionPolicy.allowsTool(browser, List.of(), "site_auth_check", "web"));
        assertFalse(InteractionExecutionPolicy.allowsTool(browser, List.of(), "web_forged", "web"));
        assertFalse(com.javaclaw.agent.ToolRiskRegistry.isHostInteractionTool("desktop_session_forged"));
        assertFalse(com.javaclaw.agent.ToolRiskRegistry.isHostInteractionTool(null));
        assertFalse(com.javaclaw.agent.ToolRiskRegistry.isHostToolInGroup("web_navigate", null));
    }

    private static TaskCriterionV3 criterion(String id, String capability) {
        return new TaskCriterionV3(id, id, capability, CapabilityMetadata.TargetKind.RESOURCE,
                "target", EffectReceiptV1.Status.OBSERVED, "");
    }
}
