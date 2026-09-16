package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteLoginViewTest {
    @Test
    void 登录列表可按当前网站过滤且无参数保留全部会话() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        support.service = (caller, service, payload) -> support.payloads.encode(
                new SiteContracts.LoginSessionList(List.of(session("first"), session("second")), true));
        try (SiteExtension extension = new SiteExtension()) {
            var started = support.start(extension);
            assertEquals(2, query(support, started, Map.of()).rows().size());
            ViewQueryResult selected = query(support, started, Map.of("siteId", "second"));
            assertEquals(1, selected.rows().size());
            assertEquals(
                    "second",
                    support.payloads
                            .decode(selected.rows().getFirst(), SiteContracts.LoginSession.class)
                            .siteId());
            assertTrue(
                    query(support, started, Map.of("siteId", "missing")).rows().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> query(support, started, Map.of("siteId", " ")));
            assertThrows(IllegalArgumentException.class, () -> query(support, started, Map.of("unknown", "first")));
        }
    }

    @Test
    void 网站列表只展示摘要且危险操作绑定当前编辑源版本() {
        ViewSchema view = SiteManagementView.create(BuiltinExtensionIds.SITE);
        ViewSchema.Table sites = (ViewSchema.Table) node(view, "sites");
        assertEquals(
                List.of("name", "origin", "enabled"),
                sites.columns().stream().map(ViewSchema.Column::field).toList());
        assertTrue(sites.actions().isEmpty());
        ViewSchema.Card actions = (ViewSchema.Card) node(view, "site-actions");
        assertEquals(
                Set.of(SiteManagement.CREDENTIAL_CLEAR, SiteManagement.PRIVATE_NETWORK_CLEAR, "delete"),
                actions.actions().stream()
                        .map(action -> action.command())
                        .collect(java.util.stream.Collectors.toSet()));
        actions.actions().forEach(action -> {
            assertTrue(action.dangerous());
            assertTrue(action.rowArguments().isEmpty());
            assertEquals(
                    new ExpectedRevisionBinding.SourceRevision(SiteManagement.EDIT_SOURCE), action.expectedRevision());
            assertTrue(action.commandBindings().stream()
                    .anyMatch(binding -> binding.binding().equals(new ViewBinding(SiteManagement.EDIT_SOURCE, "id"))));
        });
    }

    @Test
    void 开始登录使用父网站数据源而不提供第二个网站选择器() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        try (SiteExtension extension = new SiteExtension()) {
            ViewSchema view = support.start(extension).contributions().stream()
                    .filter(ExtensionContributions.View.class::isInstance)
                    .map(ExtensionContributions.View.class::cast)
                    .filter(item -> item.contributionId().equals("site.browser-login"))
                    .map(ExtensionContributions.View::view)
                    .findFirst()
                    .orElseThrow();
            assertFalse(view.nodes().stream().anyMatch(node -> node.id().equals("login-sites")));
            var begin = ((ViewSchema.Card) node(view, "login-start")).actions().getFirst();
            assertEquals("login.begin", begin.command());
            assertEquals(new ExpectedRevisionBinding.None(), begin.expectedRevision());
            assertEquals(
                    List.of("siteId", "expectedRevision", "expectedAuthorityRevision"),
                    begin.commandBindings().stream()
                            .map(binding -> binding.argumentName())
                            .toList());
            assertTrue(begin.commandBindings().stream()
                    .allMatch(binding -> binding.binding().sourceId().equals("documents")));
        }
    }

    private static ViewQueryResult query(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            Map<String, String> arguments)
            throws Exception {
        return support.decode(
                started.query(support.request(
                        "login.view",
                        new ViewQueryRequest("loginSessions", arguments, "", 100, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
    }

    private static SiteContracts.LoginSession session(String site) {
        return new SiteContracts.LoginSession(
                UUID.randomUUID().toString(),
                site,
                1,
                1,
                SiteContracts.LoginSessionState.READY,
                BuiltinExtensionTestSupport.NOW,
                BuiltinExtensionTestSupport.NOW.plusSeconds(600),
                Optional.empty());
    }

    private static ViewSchema.Node node(ViewSchema view, String id) {
        return view.nodes().stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
