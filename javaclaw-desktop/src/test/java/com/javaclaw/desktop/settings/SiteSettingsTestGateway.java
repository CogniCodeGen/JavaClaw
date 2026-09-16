package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ViewSchemaWireCodec;

/** 网站页测试的 SDK 替身；分页、选择、写回执与通知独立控制，不启动服务器或浏览器。 */
final class SiteSettingsTestGateway implements ExtensionSettingsGateway {
    final List<SiteContracts.Projection> sites = new ArrayList<>(List.of(site("a", "文档网站"), site("b", "团队网站")));
    final List<String> accountQueries = new ArrayList<>();
    final List<ViewCommandInvocation> commands = new ArrayList<>();
    final List<ViewLoadRequest> requests = new ArrayList<>();
    final CanonicalJson json = new CanonicalJson();
    int pageSize = 100;
    CompletableFuture<ExtensionRpcContracts.CallResult> command;
    CompletableFuture<ViewData> nextLoad;
    Consumer<ExtensionRpcContracts.ExtensionEvent> listener = ignored -> {};
    private final ViewSchema schema = SiteSettingsTestSchema.create();

    @Override
    public CompletableFuture<List<ExtensionRpcContracts.ViewDocument>> list(String extensionId) {
        return CompletableFuture.completedFuture(List.of(new ExtensionRpcContracts.ViewDocument(
                BuiltinExtensionIds.SITE, schema.viewId(), new ViewSchemaWireCodec(json).encode(schema))));
    }

    @Override
    public CompletableFuture<ViewData> load(
            WorkspaceId workspace,
            ExtensionRpcContracts.ViewDocument document,
            ViewSchema definition,
            ViewLoadRequest request) {
        requests.add(request);
        if (nextLoad != null) {
            CompletableFuture<ViewData> pending = nextLoad;
            nextLoad = null;
            return pending;
        }
        return CompletableFuture.completedFuture(data(request));
    }

    ViewData data(ViewLoadRequest request) {
        int offset = request.cursor("documents").isEmpty() ? 0 : Integer.parseInt(request.cursor("documents"));
        List<SiteContracts.Projection> page =
                sites.stream().skip(offset).limit(pageSize).toList();
        boolean more = offset + page.size() < sites.size();
        Optional<SiteContracts.Projection> selected = request.selectedKey("documents")
                .flatMap(
                        id -> page.stream().filter(site -> site.id().equals(id)).findFirst());
        ViewData.Source documents = new ViewData.Source(
                page.stream().map(SiteSettingsTestGateway::row).toList(),
                Map.of(),
                request.cursor("documents"),
                more ? Integer.toString(offset + page.size()) : "",
                more,
                1,
                request.pageIndex("documents"),
                selected.map(SiteContracts.Projection::id));
        Map<String, Object> editor =
                selected.map(SiteSettingsTestGateway::editor).orElse(Map.of());
        return new ViewData(Map.of(
                "documents", documents,
                "siteEditor",
                        source(
                                editor,
                                selected.map(SiteContracts.Projection::revision).orElse(0L)),
                "newSite", source(Map.of(), 0),
                "siteCredentials", ViewData.Source.empty(),
                "sitePrivateNetworkGrants", ViewData.Source.empty()));
    }

    @Override
    public CompletableFuture<ExtensionRpcContracts.CallResult> execute(
            WorkspaceId workspace, String extension, ViewCommandInvocation invocation) {
        commands.add(invocation);
        if (command != null) {
            return command;
        }
        String id = (String) invocation.arguments().get("id");
        SiteContracts.Projection value =
                site(id, invocation.arguments().getOrDefault("name", "网站").toString());
        if (invocation.operation().equals("site/create")) {
            sites.add(value);
        } else if (invocation.operation().equals("delete")) {
            sites.removeIf(site -> site.id().equals(id));
        } else if (invocation.operation().equals("site/update")) {
            sites.replaceAll(site -> site.id().equals(id) ? value : site);
        }
        return CompletableFuture.completedFuture(
                new ExtensionRpcContracts.CallResult(json.encode(value), value.revision()));
    }

    @Override
    public CompletableFuture<ExtensionRpcContracts.CallResult> query(
            WorkspaceId workspace, String extension, String operation, CanonicalPayload input) {
        if (operation.equals("account/list")) {
            String siteId =
                    json.decode(input, SiteAccountContracts.ListRequest.class).siteId();
            accountQueries.add(siteId);
            var account = new SiteAccountContracts.AccountProjection(
                    "account-" + siteId, siteId, 1, 1, 1, "工作账号", true, true, false, false, Instant.EPOCH);
            return CompletableFuture.completedFuture(new ExtensionRpcContracts.CallResult(
                    json.encode(new SiteAccountContracts.AccountList(List.of(account), Map.of())), 1));
        }
        return CompletableFuture.failedFuture(new IllegalArgumentException("测试不应发起 " + operation));
    }

    @Override
    public CompletableFuture<AttachmentRef> upload(WorkspaceId workspace, ViewAttachmentUploadRequest request) {
        return CompletableFuture.failedFuture(new AssertionError("网站页不上传附件"));
    }

    @Override
    public DesktopNotificationSubscription subscribe(
            WorkspaceId workspace, String extension, Consumer<ExtensionRpcContracts.ExtensionEvent> value) {
        listener = value;
        return () -> listener = ignored -> {};
    }

    static SiteContracts.Projection site(String id, String name) {
        URI origin = URI.create("https://" + id + ".example.com");
        return new SiteContracts.Projection(id, 1, 1, name, origin, Set.of(origin), false, false, true, Instant.EPOCH);
    }

    private static ViewData.Source source(Map<String, Object> values, long revision) {
        return new ViewData.Source(List.of(), values, "", "", false, revision, 0, Optional.empty());
    }

    private static Map<String, Object> editor(SiteContracts.Projection site) {
        Map<String, Object> result = new LinkedHashMap<>(row(site));
        result.put(
                "allowedOrigins",
                List.of(Map.of("itemKey", "origin-1", "origin", site.origin().toString())));
        result.put("credentialKind", "BEARER");
        result.put("credentialId", "");
        result.put("apiKeyHeader", "");
        result.put("privateNetworkGrantId", "");
        return Map.copyOf(result);
    }

    private static Map<String, Object> row(SiteContracts.Projection site) {
        return Map.of(
                "id",
                site.id(),
                "name",
                site.name(),
                "origin",
                site.origin().toString(),
                "allowedOrigins",
                List.of(site.origin().toString()),
                "enabled",
                site.enabled(),
                "hasCredential",
                false,
                "hasPrivateGrant",
                false,
                "revision",
                site.revision(),
                "authorityRevision",
                site.authorityRevision(),
                "updatedAt",
                site.updatedAt().toString());
    }
}
