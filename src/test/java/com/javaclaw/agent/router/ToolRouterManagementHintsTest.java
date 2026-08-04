package com.javaclaw.agent.router;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRouterManagementHintsTest {

    @Test
    void siteCredentialRequestAlwaysActivatesWebGroup() {
        RoutingResult modelResult = new RoutingResult(
                List.of("knowledge", "system"), List.of(), List.of(), List.of());

        RoutingResult result = ToolRouter.applyDeterministicManagementHints(
                "将这个能力保存到技能，并且记录这个站点到凭证", modelResult);

        assertTrue(result.toolGroups().contains("web"));
        assertTrue(result.toolGroups().contains("system"));
    }

    @Test
    void shortAddSiteRequestDoesNotRequireCredentialKeyword() {
        RoutingResult result = ToolRouter.applyDeterministicManagementHints(
                "帮我添加一个站点", RoutingResult.noTools());

        assertEquals(List.of("web"), result.toolGroups());
    }

    @Test
    void mcpConfigurationRequestAlwaysActivatesMcpGroup() {
        RoutingResult result = ToolRouter.applyDeterministicManagementHints(
                "帮我添加一个 MCP Server", RoutingResult.noTools());

        assertEquals(List.of("mcp"), result.toolGroups());
    }
}
