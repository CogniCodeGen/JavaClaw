package com.javaclaw.desktop.settings;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.animation.PauseTransition;
import javafx.util.Duration;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.Session;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.protocol.ProtocolErrorCode;

/** 登记窗口的 FX 状态机；只读轮询可以取消，写操作不自动重放，完成不明时只查询原会话。 */
final class SiteRegistrationPresenter {
    enum Phase { EDITING, STARTING, START_FAILED, ACTIVE, AUTHORIZING, COMMITTING, CANCELLING, UNKNOWN, TERMINAL }

    /** @param phase 本地交互阶段 @param session 脱敏会话 @param message 用户状态说明 @param reading 是否正在查询 */
    record Snapshot(Phase phase, Optional<Session> session, String message, boolean reading) {}

    private final ExtensionSettingsGateway gateway;
    private final WorkspaceId workspace;
    private final Consumer<SiteRegistrationContracts.Completed> completed;
    private final PauseTransition poll = new PauseTransition(Duration.seconds(1));
    private final CommandOptions beginIdentity = CommandOptions.create(0);
    private final CommandOptions cancelIdentity = CommandOptions.create(0);
    private Consumer<Snapshot> listener = ignored -> {};
    private Snapshot state = new Snapshot(Phase.EDITING, Optional.empty(), "输入网址，在隔离浏览器中完成登录后点击完成添加。", false);
    private SiteRegistrationContracts.BeginRequest beginning;
    private CompletableFuture<?> reading;
    private long epoch;
    private boolean closed;
    private boolean delivered;

    SiteRegistrationPresenter(ExtensionSettingsGateway gateway, WorkspaceId workspace,
            Consumer<SiteRegistrationContracts.Completed> completed) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.completed = Objects.requireNonNull(completed, "completed");
        poll.setOnFinished(ignored -> refresh());
    }

    void subscribe(Consumer<Snapshot> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    Snapshot state() {
        return state;
    }

    boolean pending() {
        return switch (state.phase()) {
            case STARTING, AUTHORIZING, COMMITTING, CANCELLING -> true;
            default -> false;
        };
    }

    void begin(String address) {
        if (closed || !(state.phase() == Phase.EDITING || state.phase() == Phase.START_FAILED)) {
            return;
        }
        try {
            if (beginning == null) {
                beginning = new SiteRegistrationContracts.BeginRequest(URI.create(address.strip()));
            }
            write(Phase.STARTING, "正在打开隔离浏览器…",
                    () -> gateway.beginRegistration(workspace, beginning, beginIdentity));
        } catch (IllegalArgumentException invalid) {
            publish(Phase.EDITING, "请输入不含用户名密码的 HTTPS 地址。", false);
        }
    }

    void allowOrigin(String address) {
        if (!editable()) {
            return;
        }
        try {
            Session session = state.session().orElseThrow();
            var request = new SiteRegistrationContracts.OriginRequest(
                    session.sessionId(), session.access().generation(), URI.create(address.strip()));
            CommandOptions identity = CommandOptions.create(0);
            write(Phase.AUTHORIZING, "正在允许明确输入的来源…",
                    () -> gateway.allowRegistrationOrigin(workspace, request, identity));
        } catch (IllegalArgumentException invalid) {
            publish(Phase.ACTIVE, "请输入精确 HTTPS 来源，不包含路径、查询、片段或通配符。", false);
        }
    }

    void complete(String name, Optional<String> credential) {
        if (!editable()) {
            return;
        }
        Session session = state.session().orElseThrow();
        try {
            validateCredential(session, credential);
            var request = new SiteRegistrationContracts.CompleteRequest(session.sessionId(),
                    session.access().generation(), session.page().pageRevision(), credential, name);
            CommandOptions identity = CommandOptions.create(0);
            write(Phase.COMMITTING, "正在保存网站、默认账号和登录态…",
                    () -> gateway.completeRegistration(workspace, request, identity));
        } catch (IllegalArgumentException invalid) {
            publish(Phase.ACTIVE, "请检查网站名称、当前页面和所选密码候选。", false);
        }
    }

    void refresh() {
        if (closed || pending() || reading != null || state.session().isEmpty() || state.phase() == Phase.TERMINAL) {
            return;
        }
        poll.stop();
        long requestEpoch = ++epoch;
        publish(state.phase(), state.message(), true);
        try {
            Session current = state.session().orElseThrow();
            var request = gateway.registrationStatus(workspace,
                    new SiteRegistrationContracts.SessionRequest(current.sessionId()));
            reading = request;
            request.whenComplete((value, failure) -> FxStateDispatcher.dispatch(
                    () -> readCompleted(requestEpoch, value, failure)));
        } catch (RuntimeException failure) {
            readCompleted(requestEpoch, null, failure);
        }
    }

    void cancel() {
        if (closed || pending() || state.phase() == Phase.UNKNOWN || state.session().isEmpty()) {
            return;
        }
        Session session = state.session().orElseThrow();
        if (session.state() == State.ACTIVE) {
            write(Phase.CANCELLING, "正在关闭隔离浏览器并清理临时输入…", () -> gateway.cancelRegistration(
                    workspace, new SiteRegistrationContracts.SessionRequest(session.sessionId()), cancelIdentity));
        }
    }

    /** 离开作用域时清理临时会话；提交不明不能与可能已完成的事务竞争取消。 */
    void close() {
        if (closed) {
            return;
        }
        boolean uncertain = state.phase() == Phase.COMMITTING || state.phase() == Phase.UNKNOWN;
        closed = true;
        stopRead();
        if (!uncertain) {
            state.session().filter(value -> value.state() == State.ACTIVE).ifPresent(this::cleanup);
        }
        listener = ignored -> {};
    }

    private boolean editable() {
        return !closed && state.phase() == Phase.ACTIVE && !state.reading();
    }

    private void write(Phase phase, String message, Supplier<CompletableFuture<Session>> operation) {
        stopRead();
        long requestEpoch = epoch;
        publish(phase, message, false);
        try {
            operation.get().whenComplete((value, failure) -> FxStateDispatcher.dispatch(
                    () -> writeCompleted(requestEpoch, phase, value, failure)));
        } catch (RuntimeException failure) {
            writeCompleted(requestEpoch, phase, null, failure);
        }
    }

    private void writeCompleted(long requestEpoch, Phase action, Session value, Throwable failure) {
        if (closed || requestEpoch != epoch) {
            if (action == Phase.STARTING && value != null) {
                cleanup(value);
            }
            return;
        }
        if (failure == null) {
            accept(value, false);
        } else if (action == Phase.STARTING) {
            publish(Phase.START_FAILED, "启动未确认；重试将恢复同一次启动，不会重复打开浏览器。", false);
        } else if (knownRejection(failure)) {
            publish(Phase.ACTIVE, "请求已拒绝，请刷新后检查页面或授权状态。", false);
            refresh();
        } else {
            publish(Phase.UNKNOWN, "操作结果尚未确认，正在查询原会话；不会自动再次提交。", false);
            schedule();
        }
    }

    private void readCompleted(long requestEpoch, Session value, Throwable failure) {
        if (closed || requestEpoch != epoch) {
            return;
        }
        reading = null;
        if (failure == null) {
            accept(value, state.phase() == Phase.UNKNOWN);
        } else {
            publish(state.phase(), "暂时无法读取会话，稍后继续查询；不会再次提交保存。", false);
            schedule();
        }
    }

    private void accept(Session value, boolean uncertain) {
        if (value == null || state.session().filter(old -> !old.sessionId().equals(value.sessionId())).isPresent()) {
            publish(Phase.UNKNOWN, "会话身份未确认，保留原会话等待查询。", false);
            schedule();
            return;
        }
        Phase phase = value.state() == State.ACTIVE ? (uncertain ? Phase.UNKNOWN : Phase.ACTIVE) : Phase.TERMINAL;
        state = new Snapshot(phase, Optional.of(value), message(value.state(), uncertain), false);
        listener.accept(state);
        if (value.state() == State.COMPLETED && !delivered) {
            delivered = true;
            completed.accept(value.completed().orElseThrow());
        }
        schedule();
    }

    private void publish(Phase phase, String message, boolean loading) {
        state = new Snapshot(phase, state.session(), message, loading);
        listener.accept(state);
    }

    private void schedule() {
        if (!closed && state.session().isPresent() && state.phase() != Phase.TERMINAL) {
            poll.playFromStart();
        }
    }

    private void stopRead() {
        epoch++;
        poll.stop();
        CompletableFuture<?> previous = reading;
        reading = null;
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void cleanup(Session session) {
        if (session.state() == State.ACTIVE) {
            try {
                gateway.cancelRegistration(workspace,
                        new SiteRegistrationContracts.SessionRequest(session.sessionId()), cancelIdentity);
            } catch (RuntimeException ignored) {
                // 连接中断后仍由宿主到期清理；UI 不以新幂等身份重复提交取消。
            }
        }
    }

    private static void validateCredential(Session session, Optional<String> credential) {
        URI origin = SiteContracts.originOf(session.page().uri().orElseThrow(
                () -> new IllegalArgumentException("当前页面尚未就绪")));
        if (credential.isPresent() && session.page().candidates().stream().noneMatch(candidate ->
                candidate.id().equals(credential.orElseThrow()) && candidate.origin().equals(origin))) {
            throw new IllegalArgumentException("所选密码候选不属于当前页面来源");
        }
    }

    private static boolean knownRejection(Throwable failure) {
        Throwable cause = SettingsFailures.unwrap(failure);
        if (!(cause instanceof RemoteRpcException remote)) {
            return false;
        }
        return remote.code() == ProtocolErrorCode.INVALID_PARAMS || remote.code() == ProtocolErrorCode.REVISION_CONFLICT
                || remote.code() == ProtocolErrorCode.PERMISSION_DENIED;
    }

    private static String message(State state, boolean uncertain) {
        return switch (state) {
            case ACTIVE -> uncertain ? "仍在确认操作结果；只查询状态，不会重复提交。" : "请在浏览器中完成登录，再点击完成添加。";
            case COMPLETED -> "网站与默认账号已添加。";
            case CANCELLED -> "已取消添加，临时登录输入已清理。";
            case EXPIRED -> "登记会话已到期，请重新添加。";
            case FAILED -> "登记未完成，临时会话已结束。";
        };
    }
}
