package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ViewSchemaWireCodec;

/** 登录分区使用固定声明式节点与可控制 Future，不启动浏览器或读取真实账号。 */
final class SiteLoginSettingsTestGateway implements ExtensionSettingsGateway {
    final CanonicalJson json = new CanonicalJson();
    final List<ViewQueryRequest> queries = new ArrayList<>();
    final List<ViewCommandInvocation> commands = new ArrayList<>();
    CompletableFuture<ExtensionRpcContracts.CallResult> query;
    CompletableFuture<ExtensionRpcContracts.CallResult> command = new CompletableFuture<>();
    boolean enabled = true;
    boolean available = true;
    boolean failSynchronously;
    int lists;

    @Override
    public CompletableFuture<List<ExtensionRpcContracts.ViewDocument>> list(String extensionId) {
        lists++;
        ViewSchema schema = schema();
        return CompletableFuture.completedFuture(
                enabled
                        ? List.of(new ExtensionRpcContracts.ViewDocument(
                                BuiltinExtensionIds.SITE,
                                schema.viewId(),
                                new ViewSchemaWireCodec(json).encode(schema)))
                        : List.of());
    }

    @Override
    public CompletableFuture<ViewData> load(
            WorkspaceId workspace,
            ExtensionRpcContracts.ViewDocument document,
            ViewSchema schema,
            ViewLoadRequest request) {
        throw new AssertionError("登录分区不另行读取网站目录");
    }

    @Override
    public CompletableFuture<ExtensionRpcContracts.CallResult> query(
            WorkspaceId workspace, String extension, String operation, CanonicalPayload input) {
        if (!operation.equals("login.view")) {
            throw new AssertionError("只允许查询登录会话");
        }
        ViewQueryRequest request = json.decode(input, ViewQueryRequest.class);
        queries.add(request);
        return query == null
                ? CompletableFuture.completedFuture(response(request.arguments().get("siteId")))
                : query;
    }

    ExtensionRpcContracts.CallResult response(String site) {
        SiteContracts.LoginSession session = new SiteContracts.LoginSession(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                site,
                1,
                1,
                SiteContracts.LoginSessionState.READY,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(600),
                Optional.empty());
        return new ExtensionRpcContracts.CallResult(
                json.encode(new ViewQueryResult(
                        "loginSessions",
                        List.of(json.encode(session)),
                        json.encode(Map.of("interactiveLoginAvailable", available)),
                        "",
                        false,
                        0)),
                0);
    }

    @Override
    public CompletableFuture<ExtensionRpcContracts.CallResult> execute(
            WorkspaceId workspace, String extension, ViewCommandInvocation invocation) {
        commands.add(invocation);
        if (failSynchronously) {
            throw new IllegalStateException("服务暂时不可用");
        }
        return command;
    }

    @Override
    public CompletableFuture<AttachmentRef> upload(WorkspaceId workspace, ViewAttachmentUploadRequest request) {
        throw new AssertionError("登录分区不允许附件上传");
    }

    @Override
    public DesktopNotificationSubscription subscribe(
            WorkspaceId workspace, String extension, Consumer<ExtensionRpcContracts.ExtensionEvent> listener) {
        return () -> {};
    }

    static ViewSchema schema() {
        ViewAction begin = new ViewAction(
                "开始隔离登录",
                "login.begin",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.None(),
                false,
                new ViewCommandBinding("siteId", new ViewBinding("documents", "id")),
                new ViewCommandBinding("expectedRevision", new ViewBinding("documents", "revision")),
                new ViewCommandBinding("expectedAuthorityRevision", new ViewBinding("documents", "authorityRevision")));
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
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "site.browser-login",
                "网页登录",
                List.of(
                        new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                        new ViewDataSource("loginSessions", "login.view", Map.of(), List.of(), 100)),
                List.of(
                        new ViewSchema.Card("login-start", "登录当前网站", "仅登录所选网站", List.of(begin)),
                        new ViewSchema.Table(
                                "login-sessions",
                                "登录会话",
                                "loginSessions",
                                "sessionId",
                                List.of(new ViewSchema.Column("state", "状态", Optional.of(100))),
                                ViewSelectionMode.SINGLE,
                                List.of(save, cancel))));
    }
}
