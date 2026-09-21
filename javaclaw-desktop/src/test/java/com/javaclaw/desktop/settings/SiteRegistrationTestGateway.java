package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.Session;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 固定登记数据和可控异步回执，不打开网络浏览器或读取真实凭据。 */
final class SiteRegistrationTestGateway implements ExtensionSettingsGateway {
    static final String SESSION = "40a90908-698c-403d-879d-11c54bbb5d6e";
    static final URI ORIGIN = URI.create("https://z.example.com");
    final SiteSettingsTestGateway settings = new SiteSettingsTestGateway();
    final List<Invocation> calls = new ArrayList<>();
    CompletableFuture<Session> begin = CompletableFuture.completedFuture(session(State.ACTIVE));
    CompletableFuture<Session> status = CompletableFuture.completedFuture(session(State.ACTIVE));
    CompletableFuture<Session> origin = CompletableFuture.completedFuture(session(State.ACTIVE));
    CompletableFuture<Session> complete = new CompletableFuture<>();
    CompletableFuture<Session> cancel = CompletableFuture.completedFuture(session(State.CANCELLED));

    @Override
    public CompletableFuture<Session> beginRegistration(
            WorkspaceId workspace, SiteRegistrationContracts.BeginRequest request, CommandOptions options) {
        calls.add(new Invocation("begin", workspace, request, options));
        return begin;
    }

    @Override
    public CompletableFuture<Session> registrationStatus(
            WorkspaceId workspace, SiteRegistrationContracts.SessionRequest request) {
        calls.add(new Invocation("status", workspace, request, null));
        return status;
    }

    @Override
    public CompletableFuture<Session> allowRegistrationOrigin(
            WorkspaceId workspace, SiteRegistrationContracts.OriginRequest request, CommandOptions options) {
        calls.add(new Invocation("origin", workspace, request, options));
        return origin;
    }

    @Override
    public CompletableFuture<Session> completeRegistration(
            WorkspaceId workspace, SiteRegistrationContracts.CompleteRequest request, CommandOptions options) {
        calls.add(new Invocation("complete", workspace, request, options));
        return complete;
    }

    @Override
    public CompletableFuture<Session> cancelRegistration(
            WorkspaceId workspace, SiteRegistrationContracts.SessionRequest request, CommandOptions options) {
        calls.add(new Invocation("cancel", workspace, request, options));
        return cancel;
    }

    @Override
    public CompletableFuture<List<ExtensionRpcContracts.ViewDocument>> list(String extensionId) {
        return settings.list(extensionId);
    }

    @Override
    public CompletableFuture<ViewData> load(
            WorkspaceId workspace,
            ExtensionRpcContracts.ViewDocument document,
            ViewSchema schema,
            ViewLoadRequest request) {
        return settings.load(workspace, document, schema, request);
    }

    @Override
    public CompletableFuture<ExtensionRpcContracts.CallResult> execute(
            WorkspaceId workspace, String extension, ViewCommandInvocation invocation) {
        return settings.execute(workspace, extension, invocation);
    }

    @Override
    public CompletableFuture<ExtensionRpcContracts.CallResult> query(
            WorkspaceId workspace, String extension, String operation, CanonicalPayload arguments) {
        return settings.query(workspace, extension, operation, arguments);
    }

    @Override
    public CompletableFuture<AttachmentRef> upload(WorkspaceId workspace, ViewAttachmentUploadRequest request) {
        return settings.upload(workspace, request);
    }

    @Override
    public DesktopNotificationSubscription subscribe(
            WorkspaceId workspace, String extension, Consumer<ExtensionRpcContracts.ExtensionEvent> listener) {
        return settings.subscribe(workspace, extension, listener);
    }

    long count(String operation) {
        return calls.stream().filter(call -> call.operation().equals(operation)).count();
    }

    static Session session(State state) {
        var candidate = new SiteRegistrationContracts.CredentialCandidate("candidate", ORIGIN, "此网站的登录表单");
        return new Session(
                SESSION,
                state,
                new SiteRegistrationContracts.Access(
                        2,
                        Set.of(ORIGIN),
                        Set.of(URI.create("https://login.example.com")),
                        Instant.parse("2030-01-01T00:00:00Z")),
                new SiteRegistrationContracts.Page(
                        3, Optional.of(URI.create(ORIGIN + "/dashboard")), "个人工作台", List.of(candidate)),
                state == State.COMPLETED
                        ? Optional.of(new SiteRegistrationContracts.Completed("z", "default-z", ORIGIN))
                        : Optional.empty());
    }

    /** @param operation 测试调用名 @param workspace 固定工作区 @param request 脱敏请求 @param options 查询时为空 */
    record Invocation(String operation, WorkspaceId workspace, Object request, CommandOptions options) {}
}
