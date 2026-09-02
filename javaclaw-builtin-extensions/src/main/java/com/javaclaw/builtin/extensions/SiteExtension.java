package com.javaclaw.builtin.extensions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** Site 领域扩展；只发现显式启用且由 HTTPS 主机白名单约束的站点。 */
final class SiteExtension implements ExtensionBundle {
    private final SiteDefinitionLifecycle lifecycle = new SiteDefinitionLifecycle();
    private final ManagedDocumentResource<SiteContracts.Site> documents = new ManagedDocumentResource<>(
            BuiltinExtensionIds.SITE,
            "站点",
            SiteContracts.Site.class,
            Set.of(ContributionKind.SERVICE),
            SitePermission.create(),
            lifecycle);
    private final SiteManagement management = new SiteManagement(documents, lifecycle);
    private final SitePublicDocuments publicDocuments = new SitePublicDocuments(documents);

    @Override
    public ExtensionDescriptor descriptor() {
        return documents.descriptor();
    }

    @Override
    public List<ExtensionContribution> start(ExtensionContext context) {
        List<ExtensionContribution> contributions = new ArrayList<>(documents.startWithManagedWrites(context, false));
        contributions.addAll(publicDocuments.contributions());
        contributions.addAll(management.contributions());
        contributions.addAll(List.of(
                new ExtensionContributions.Query("site.search.query", Set.of("search"), this::search),
                new ExtensionContributions.Query(
                        "site.login.query", Set.of("login.status", "login.list", "login.view"), this::loginQuery),
                new ExtensionContributions.Command(
                        "site.login.command", Set.of("login.begin", "login.save", "login.cancel"), this::loginCommand),
                new ExtensionContributions.Tool(
                        "site.search.tool",
                        documents.readOnlyTool("search", "发现启用的受限站点配置", searchSchema(), Set.of("browser", "https")),
                        this::search),
                new ExtensionContributions.Tool(
                        "site.snapshot.tool",
                        documents.governedTool(
                                "snapshot",
                                "通过隔离 Browser Worker 读取受 Site 白名单约束的页面正文",
                                snapshotSchema(),
                                ToolRisk.NETWORK,
                                Set.of("browser", "page", "https")),
                        this::snapshot),
                new ExtensionContributions.Resource(
                        "site.browser-worker",
                        ContributionKind.SERVICE,
                        documents
                                .payloads()
                                .encode(Map.of(
                                        "isolation",
                                        "process",
                                        "networkPolicy",
                                        "host-broker-only",
                                        "rawNetwork",
                                        false,
                                        "schemaVersion",
                                        2))),
                new ExtensionContributions.View("site.browser-login", loginView())));
        return List.copyOf(contributions);
    }

    @Override
    public List<ExtensionSchema> schemas() {
        return documents.schemas();
    }

    @Override
    public void close() {
        documents.close();
    }

    private ExtensionResponse loginQuery(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case "login.status" ->
                invokeLogin(
                        context,
                        SiteContracts.BROWSER_LOGIN_STATUS_SERVICE,
                        request.payload(),
                        SiteContracts.LoginSession.class);
            case "login.list" ->
                invokeLogin(
                        context,
                        SiteContracts.BROWSER_LOGIN_LIST_SERVICE,
                        documents.payloads().encode(Map.of()),
                        SiteContracts.LoginSessionList.class);
            case "login.view" -> loginViewData(request, context);
            default -> throw new IllegalArgumentException("unknown Site login query operation");
        };
    }

    private ExtensionResponse loginCommand(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        if (request.expectedRevision() != 0) {
            throw new IllegalArgumentException("Site login session command expected revision must be 0");
        }
        return switch (request.operation()) {
            case "login.begin" -> idempotentExternal(request, context, () -> beginLogin(request, context));
            case "login.save" -> saveLogin(request, context);
            case "login.cancel" ->
                idempotentExternal(
                        request,
                        context,
                        () -> invokeLogin(
                                context,
                                SiteContracts.BROWSER_LOGIN_CANCEL_SERVICE,
                                request.payload(),
                                SiteContracts.LoginSession.class));
            default -> throw new IllegalArgumentException("unknown Site login command operation");
        };
    }

    private ExtensionResponse idempotentExternal(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            java.util.concurrent.Callable<ExtensionResponse> action)
            throws Exception {
        String key = requiredIdempotencyKey(request);
        String digest = request.payload().sha256();
        Optional<ExtensionResponse> recovered =
                context.managedStore().recoverCommand(documents.extensionId(), request.operation(), key, digest);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        ExtensionResponse response = action.call();
        return context.managedStore()
                .inCommand(documents.extensionId(), request.operation(), key, digest, transaction -> response);
    }

    private ExtensionResponse beginLogin(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        SiteContracts.LoginBeginRequest input =
                documents.payloads().decode(request.payload(), SiteContracts.LoginBeginRequest.class);
        SiteContracts.Site site = documents.requireDocument(input.siteId(), input.expectedRevision(), context);
        if (!site.enabled() || site.authorityRevision() != input.expectedAuthorityRevision()) {
            throw new IllegalArgumentException("Site is disabled or its authority revision changed");
        }
        String idempotencyKey = requiredIdempotencyKey(request);
        String sessionId = java.util
                .UUID
                .nameUUIDFromBytes((context.workspaceId() + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8))
                .toString();
        SiteContracts.LoginBeginTask task = new SiteContracts.LoginBeginTask(site, sessionId, Duration.ofMinutes(10));
        return invokeLogin(
                context,
                SiteContracts.BROWSER_LOGIN_BEGIN_SERVICE,
                documents.payloads().encode(task),
                SiteContracts.LoginSession.class);
    }

    private ExtensionResponse saveLogin(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        SiteContracts.LoginControlRequest input =
                documents.payloads().decode(request.payload(), SiteContracts.LoginControlRequest.class);
        SiteContracts.LoginSaveTask task = new SiteContracts.LoginSaveTask(
                input.sessionId(),
                requiredIdempotencyKey(request),
                request.payload().sha256());
        CanonicalPayload response = context.services()
                .invoke(invocation(
                        context,
                        SiteContracts.BROWSER_LOGIN_SAVE_SERVICE,
                        documents.payloads().encode(task)));
        SiteContracts.LoginSaveCommit commit =
                documents.payloads().decode(response, SiteContracts.LoginSaveCommit.class);
        SiteContracts.LoginSaveResult result =
                new SiteContracts.LoginSaveResult(SiteContracts.Projection.from(commit.site()), true, commit.session());
        return new ExtensionResponse(
                documents.payloads().encode(result), result.site().revision());
    }

    private ExtensionResponse loginViewData(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"loginSessions".equals(query.dataSourceId()) || !query.arguments().isEmpty()) {
            throw new IllegalArgumentException("unknown Site login view data source");
        }
        CanonicalPayload response = context.services()
                .invoke(invocation(
                        context,
                        SiteContracts.BROWSER_LOGIN_LIST_SERVICE,
                        documents.payloads().encode(Map.of())));
        SiteContracts.LoginSessionList result =
                documents.payloads().decode(response, SiteContracts.LoginSessionList.class);
        ViewQueryResult view = new ViewQueryResult(
                query.dataSourceId(),
                result.sessions().stream().map(documents.payloads()::encode).toList(),
                documents.payloads().encode(Map.of("interactiveLoginAvailable", result.interactiveLoginAvailable())),
                "",
                false,
                0);
        return new ExtensionResponse(documents.payloads().encode(view), 0);
    }

    private <T> ExtensionResponse invokeLogin(
            ExtensionExecutionContext context, String serviceId, CanonicalPayload payload, Class<T> responseType)
            throws Exception {
        CanonicalPayload response = context.services().invoke(invocation(context, serviceId, payload));
        documents.payloads().decode(response, responseType);
        return new ExtensionResponse(response, 0);
    }

    private IsolatedServiceInvocation invocation(
            ExtensionExecutionContext context, String serviceId, CanonicalPayload payload) {
        return new IsolatedServiceInvocation(
                documents.extensionId(),
                context.workspaceId(),
                context.effectivePermissions(),
                serviceId,
                payload,
                context.cancellation());
    }

    private static String requiredIdempotencyKey(ExtensionRequest request) {
        return request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Site login command requires idempotency key"));
    }

    private ViewSchema loginView() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "site.browser-login",
                "Site 人工登录",
                List.of(
                        new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                        new ViewDataSource("loginSessions", "login.view", Map.of(), List.of(), 100)),
                List.of(
                        new ViewSchema.Card(
                                "login-boundary",
                                "隔离登录边界",
                                "登录窗口最长存在 10 分钟。所有请求仍逐跳经过 App Server Broker；Cookie 与 storage state 只会直接密封进 Vault。未通过当前平台原生验证时启动会安全拒绝。",
                                List.of()),
                        siteLoginTable(),
                        loginSessionTable()));
    }

    private ViewSchema.Table siteLoginTable() {
        ViewAction begin = new ViewAction(
                "开始隔离登录",
                "login.begin",
                Map.of(),
                Map.of(
                        "siteId", "id",
                        "expectedRevision", "revision",
                        "expectedAuthorityRevision", "authorityRevision"),
                new ExpectedRevisionBinding.None(),
                false);
        return new ViewSchema.Table(
                "login-sites",
                "可登录 Site",
                "documents",
                "id",
                List.of(
                        new ViewSchema.Column("name", "名称", Optional.of(200)),
                        new ViewSchema.Column("origin", "HTTPS Origin", Optional.of(300)),
                        new ViewSchema.Column("revision", "版本", Optional.of(80)),
                        new ViewSchema.Column("authorityRevision", "权限版本", Optional.of(90))),
                ViewSelectionMode.SINGLE,
                List.of(begin));
    }

    private ViewSchema.Table loginSessionTable() {
        ViewAction save = new ViewAction(
                "保存登录",
                "login.save",
                Map.of(),
                Map.of("sessionId", "sessionId"),
                new ExpectedRevisionBinding.None(),
                true);
        ViewAction cancel = new ViewAction(
                "取消",
                "login.cancel",
                Map.of(),
                Map.of("sessionId", "sessionId"),
                new ExpectedRevisionBinding.None(),
                false);
        return new ViewSchema.Table(
                "login-sessions",
                "登录会话",
                "loginSessions",
                "sessionId",
                List.of(
                        new ViewSchema.Column("siteId", "Site", Optional.of(160)),
                        new ViewSchema.Column("state", "状态", Optional.of(120)),
                        new ViewSchema.Column("startedAt", "开始时间", Optional.of(180)),
                        new ViewSchema.Column("expiresAt", "到期时间", Optional.of(180))),
                ViewSelectionMode.SINGLE,
                List.of(save, cancel));
    }

    private ExtensionResponse search(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        SiteContracts.SearchRequest search =
                documents.payloads().decode(request.payload(), SiteContracts.SearchRequest.class);
        List<SiteContracts.Projection> matches = documents.documents(context).stream()
                .filter(SiteContracts.Site::enabled)
                .filter(site -> ExtensionSearch.contains(
                                search.query(), site.name(), site.origin().toASCIIString())
                        || ExtensionSearch.contains(
                                search.query(),
                                site.allowedOrigins().stream()
                                        .map(java.net.URI::toASCIIString)
                                        .toList()))
                .sorted(Comparator.comparing(SiteContracts.Site::updatedAt)
                        .reversed()
                        .thenComparing(SiteContracts.Site::id))
                .limit(search.limit())
                .map(SiteContracts.Projection::from)
                .toList();
        return new ExtensionResponse(documents.payloads().encode(new SiteContracts.SearchResult(matches)), 0);
    }

    private ExtensionResponse snapshot(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        SiteContracts.SnapshotRequest input =
                documents.payloads().decode(request.payload(), SiteContracts.SnapshotRequest.class);
        SiteContracts.Site site = documents.requireDocument(input.siteId(), input.expectedRevision(), context);
        if (!site.enabled()
                || site.authorityRevision() != input.expectedAuthorityRevision()
                || !site.allowedOrigins().contains(SiteContracts.originOf(input.uri()))) {
            throw new IllegalArgumentException("requested URI or authority revision is outside the enabled Site");
        }
        String host = input.uri().getHost().toLowerCase(java.util.Locale.ROOT);
        int port = input.uri().getPort() < 0 ? 443 : input.uri().getPort();
        if (!context.effectivePermissions().network().allowsHost(host)
                || !context.effectivePermissions().network().allowsPort(port)) {
            throw new IllegalArgumentException("effective PermissionProfile does not allow the requested host");
        }
        SiteContracts.SnapshotTask task =
                new SiteContracts.SnapshotTask(site, input.uri(), input.maxCharacters(), Duration.ofSeconds(30));
        CanonicalPayload response = context.services()
                .invoke(new IsolatedServiceInvocation(
                        documents.extensionId(),
                        context.workspaceId(),
                        context.effectivePermissions(),
                        SiteContracts.BROWSER_SNAPSHOT_SERVICE,
                        documents.payloads().encode(task),
                        context.cancellation()));
        SiteContracts.PageSnapshot snapshot = documents.payloads().decode(response, SiteContracts.PageSnapshot.class);
        if (!site.allowedOrigins().contains(SiteContracts.originOf(snapshot.uri()))) {
            throw new IllegalStateException("Browser Worker returned an Origin outside the Site allowlist");
        }
        return new ExtensionResponse(response, site.revision());
    }

    private CanonicalPayload searchSchema() {
        return documents
                .payloads()
                .encode(Map.of(
                        "additionalProperties",
                        false,
                        "properties",
                        Map.of(
                                "limit", Map.of("maximum", 100, "minimum", 1, "type", "integer"),
                                "query", Map.of("minLength", 1, "type", "string")),
                        "required",
                        List.of("query", "limit"),
                        "type",
                        "object"));
    }

    private CanonicalPayload snapshotSchema() {
        return documents
                .payloads()
                .encode(Map.of(
                        "additionalProperties",
                        false,
                        "properties",
                        Map.of(
                                "expectedRevision", Map.of("minimum", 1, "type", "integer"),
                                "expectedAuthorityRevision", Map.of("minimum", 1, "type", "integer"),
                                "maxCharacters", Map.of("maximum", 200_000, "minimum", 1, "type", "integer"),
                                "siteId", Map.of("minLength", 1, "type", "string"),
                                "uri", Map.of("format", "uri", "type", "string")),
                        "required",
                        List.of("siteId", "expectedRevision", "expectedAuthorityRevision", "uri", "maxCharacters"),
                        "type",
                        "object"));
    }
}
