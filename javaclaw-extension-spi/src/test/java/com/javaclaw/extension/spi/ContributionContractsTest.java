package com.javaclaw.extension.spi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContributionContractsTest {
    private static final ExtensionHandler HANDLER = (request, context) -> null;

    @Test
    void executableContributionKindsReportStableIdentity() {
        var tool = new ExtensionContributions.Tool(" tool ", SpiFixtures.tool("core", "read", 1), HANDLER);
        var query = new ExtensionContributions.Query("query", Set.of("read"), HANDLER);
        var command = new ExtensionContributions.Command("command", Set.of("write"), HANDLER);
        var orchestrator = new ExtensionContributions.Orchestrator("flow", Set.of("run"), (request, context) -> null);
        var timer = new ExtensionContributions.Timer("timer", Duration.ofSeconds(1), "run");

        assertEquals("tool", tool.contributionId());
        assertEquals(ContributionKind.TOOL, tool.kind());
        assertEquals(ContributionKind.QUERY, query.kind());
        assertEquals(ContributionKind.COMMAND, command.kind());
        assertEquals(ContributionKind.ORCHESTRATOR, orchestrator.kind());
        assertEquals(ContributionKind.TIMER, timer.kind());
    }

    @Test
    void resourceContributionAcceptsOnlyDeclarativeKinds() {
        for (ContributionKind kind : List.of(
                ContributionKind.CONTEXT,
                ContributionKind.SKILL,
                ContributionKind.MCP,
                ContributionKind.HOOK,
                ContributionKind.SERVICE)) {
            assertEquals(kind, new ExtensionContributions.Resource("resource", kind, SpiFixtures.payload()).kind());
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionContributions.Resource("resource", ContributionKind.TOOL, SpiFixtures.payload()));
    }

    @Test
    void contributionsRejectBlankIdentityEmptyOperationsAndNonPositiveTimer() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionContributions.Tool(" ", SpiFixtures.tool("core", "read", 1), HANDLER));
        assertThrows(
                IllegalArgumentException.class, () -> new ExtensionContributions.Query("query", Set.of(), HANDLER));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionContributions.Command("command", Set.of(" "), HANDLER));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionContributions.Orchestrator("flow", Set.of(), (request, context) -> null));
        assertThrows(
                IllegalArgumentException.class, () -> new ExtensionContributions.Timer("timer", Duration.ZERO, "run"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionContributions.Timer("timer", Duration.ofSeconds(1), " "));
    }

    @Test
    void viewContributionReportsViewKind() {
        ViewSchema schema = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "view",
                "View",
                List.of(new ViewDataSource("content", "view.read", Map.of(), List.of(), 1)),
                List.of(new ViewSchema.Markdown("body", "正文", new ViewBinding("content", "markdown"))));
        var view = new ExtensionContributions.View("page", schema);

        assertEquals(ContributionKind.VIEW, view.kind());
        assertEquals(schema, view.view());
    }
}
