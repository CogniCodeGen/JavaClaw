package com.javaclaw.desktop.shell;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.LauncherSession;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;

/**
 * 主窗口状态文字只投影权威连接及 Turn，不从输出文本猜测执行阶段。
 *
 * @param connection 连接状态文字，不可空
 * @param title 对话标题，不可空
 * @param meta 工作区或执行状态，不可空
 * @param error 会话错误提示，不可空
 * @param connectionCard 连接失败恢复卡，不可空
 * @param connectionDetail 连接恢复说明，不可空
 * @param dot 与状态文字配对的状态点，不可空
 */
record ShellStatusLabels(
        Label connection,
        Label title,
        Label meta,
        Label error,
        VBox connectionCard,
        Label connectionDetail,
        Label dot) {
    void render(DesktopState state) {
        boolean failed = state.connection().status() == ConnectionState.Status.FAILED;
        connection.setText(failed ? "App Server 未连接" : state.connection().detail());
        connectionDetail.setText(
                "无法连接本地 App Server。" + LauncherSession.current().recoveryInstruction());
        visible(connectionCard, failed);
        title.setText(
                state.threads().selectedThread().map(ConversationThread::title).orElse("新对话"));
        meta.setText(headline(state));
        meta.setTooltip(
                state.threads().activeTurn().map(ShellStatusLabels::details).orElse(null));
        dot.getStyleClass().setAll("chat-top-status-dot", dotStyle(state));
        dot.setAccessibleText(
                state.connection().status() == ConnectionState.Status.CONNECTED
                        ? meta.getText()
                        : state.connection().detail());
        error.setText(state.interaction().error().orElse(""));
        visible(error, state.interaction().error().isPresent());
    }

    private static String dotStyle(DesktopState state) {
        return switch (state.connection().status()) {
            case DISCONNECTED -> "status-idle";
            case CONNECTING -> "status-waiting";
            case FAILED -> "status-failed";
            case CONNECTED ->
                state.threads()
                        .activeTurn()
                        .map(turn -> switch (turn.status()) {
                            case RUNNING -> "status-executing";
                            case QUEUED, WAITING -> "status-waiting";
                            case FAILED -> "status-failed";
                            case COMPLETED, CANCELLED -> "status-idle";
                        })
                        .orElse("status-idle");
        };
    }

    private static String headline(DesktopState state) {
        if (state.connection().status() != ConnectionState.Status.CONNECTED) {
            return switch (state.connection().status()) {
                case CONNECTING -> "正在连接服务…";
                case FAILED -> "服务连接失败";
                default -> "服务未连接";
            };
        }
        return state.threads()
                .activeTurn()
                .map(turn -> status(turn, state))
                .orElseGet(() ->
                        state.threads().selectedWorkspace().map(Workspace::name).orElse("选择工作区，开始聊天"));
    }

    private static String status(AgentTurn turn, DesktopState state) {
        return switch (turn.status()) {
            case QUEUED -> "等待执行";
            case RUNNING -> "执行中";
            case WAITING -> waiting(state);
            case COMPLETED -> "已完成";
            case CANCELLED -> "已停止";
            case FAILED -> "执行失败";
        };
    }

    private static String waiting(DesktopState state) {
        if (!state.interaction().pendingApprovals().isEmpty()) {
            return "等待审批";
        }
        return state.interaction().inputs().pendingRequests().isEmpty() ? "等待处理" : "等待输入";
    }

    private static Tooltip details(AgentTurn turn) {
        return new Tooltip("Agent " + turn.role().id() + "@" + turn.role().revision()
                + " · 模型 " + turn.provider().model()
                + (turn.resolvedConfig().modelLocked() ? "（由 Agent 锁定）" : "")
                + " · 权限 " + turn.permissionProfile().id() + "@"
                + turn.permissionProfile().version()
                + "\n"
                + turn.resolvedConfig().provenance().stream()
                        .map(source -> source.field() + " ← " + source.source() + " / " + source.sourceId() + "@"
                                + source.revision())
                        .collect(java.util.stream.Collectors.joining("\n")));
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }
}
