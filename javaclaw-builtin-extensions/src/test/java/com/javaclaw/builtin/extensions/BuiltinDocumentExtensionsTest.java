package com.javaclaw.builtin.extensions;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteManagementContracts;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuiltinDocumentExtensionsTest {
    @Test
    void siteSnapshotRequiresDocumentNetworkAndWorkerAllowlistAgreement() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SiteExtension bundle = new SiteExtension();
        var started = support.start(bundle);
        createSite(support, started, "docs", true, "site-enabled");
        createSite(support, started, "off", false, "site-disabled");
        var snapshotRequest =
                new SiteContracts.SnapshotRequest("docs", 1, 1, URI.create("https://docs.example.com/page"), 1_000);
        assertSiteSearchAndSnapshot(support, started, snapshotRequest);
        assertSiteDocumentAllowlist(support, started);
        assertSitePermissionAndWorkerAllowlist(support, bundle, started, snapshotRequest);
    }

    private static void assertSitePermissionAndWorkerAllowlist(
            BuiltinExtensionTestSupport support,
            SiteExtension bundle,
            BuiltinExtensionTestSupport.Started started,
            SiteContracts.SnapshotRequest snapshotRequest) {
        ExtensionExecutionContext restricted = new ExtensionExecutionContext(
                bundle.descriptor(),
                support.workspaceId,
                BuiltinStoragePermission.create("restricted"),
                support.cancellation,
                support.clock,
                support.store,
                support.turns,
                support.executionPolicies,
                support.scheduleTargets,
                support.inputs,
                support.jobs,
                (workspace, thread, item, verbatim) -> false,
                support.attachments,
                support.credentials,
                support.networkGrants,
                invocation -> invocation.request(),
                support.embeddings,
                com.javaclaw.extension.spi.WorkspaceExecutionPort.denied());
        ExtensionContributions.Tool snapshotTool = started.contributions().stream()
                .filter(ExtensionContributions.Tool.class::isInstance)
                .map(ExtensionContributions.Tool.class::cast)
                .filter(tool -> tool.contributionId().equals("site.snapshot.tool"))
                .findFirst()
                .orElseThrow();
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshotTool
                        .handler()
                        .handle(support.request("snapshot", snapshotRequest, Optional.empty(), 0), restricted));

        support.service = (caller, serviceId, request) -> support.payloads.encode(
                new SiteContracts.PageSnapshot(URI.create("https://evil.example/final"), "Evil", "正文", NOW));
        assertThrows(
                IllegalStateException.class,
                () -> started.tool(
                        "site.snapshot.tool", support.request("snapshot", snapshotRequest, Optional.empty(), 0)));
        assertEquals(
                Set.of(com.javaclaw.api.NetworkPermission.ANY_PORT),
                bundle.descriptor().requirements().permissionCeiling().network().ports());
    }

    private static void assertSiteDocumentAllowlist(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started started) {
        assertThrows(
                IllegalArgumentException.class,
                () -> started.tool(
                        "site.snapshot.tool",
                        support.request(
                                "snapshot",
                                new SiteContracts.SnapshotRequest(
                                        "off", 1, 1, URI.create("https://docs.example.com/page"), 100),
                                Optional.empty(),
                                0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.tool(
                        "site.snapshot.tool",
                        support.request(
                                "snapshot",
                                new SiteContracts.SnapshotRequest(
                                        "docs", 1, 1, URI.create("https://other.example.com/page"), 100),
                                Optional.empty(),
                                0)));
    }

    private static void assertSiteSearchAndSnapshot(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            SiteContracts.SnapshotRequest snapshotRequest)
            throws Exception {
        SiteContracts.SearchResult search = support.decode(
                started.query(support.request(
                        "search", new SiteContracts.SearchRequest("DOCS.EXAMPLE.COM", 10), Optional.empty(), 0)),
                SiteContracts.SearchResult.class);
        assertEquals(
                List.of("docs"),
                search.matches().stream().map(SiteContracts.Projection::id).toList());

        support.service = (caller, serviceId, request) -> support.payloads.encode(
                new SiteContracts.PageSnapshot(URI.create("https://docs.example.com/final"), "Docs", "正文", NOW));
        SiteContracts.PageSnapshot snapshot = support.decode(
                started.tool("site.snapshot.tool", support.request("snapshot", snapshotRequest, Optional.empty(), 0)),
                SiteContracts.PageSnapshot.class);
        assertEquals("正文", snapshot.text());
    }

    @Test
    void builtinCatalogHasOneBundleForEveryPlannedDomainInStableOrder() {
        List<ExtensionBundle> bundles = BuiltinExtensions.create();
        assertEquals(10, bundles.size());
        assertEquals(
                List.of(
                        BuiltinExtensionIds.PLAN,
                        BuiltinExtensionIds.LOOP,
                        BuiltinExtensionIds.WORKFLOW,
                        BuiltinExtensionIds.SDD,
                        BuiltinExtensionIds.SCHEDULE,
                        BuiltinExtensionIds.MEMORY,
                        BuiltinExtensionIds.KNOWLEDGE,
                        BuiltinExtensionIds.SKILL,
                        BuiltinExtensionIds.SITE,
                        BuiltinExtensionIds.CODING),
                bundles.stream().map(bundle -> bundle.descriptor().id().value()).toList());
        assertEquals(
                Set.of(ExtensionAvailability.OPTIONAL),
                bundles.stream()
                        .map(bundle -> bundle.descriptor().requirements().availability())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private static long createSite(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String id,
            boolean enabled,
            String key)
            throws Exception {
        SiteManagementContracts.SaveRequest input = new SiteManagementContracts.SaveRequest(
                id,
                id + " documentation",
                URI.create("https://docs.example.com"),
                List.of(new SiteManagementContracts.AllowedOrigin("origin-1", URI.create("https://docs.example.com"))),
                enabled);
        return started.command(support.request("site/create", input, Optional.of(key), 0))
                .revision();
    }
}
