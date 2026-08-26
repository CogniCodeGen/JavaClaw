package com.javaclaw.chat;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.runtime.WorkspaceRuntime;
import com.javaclaw.task.sdd.run.SddTaskState;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Owns the read-only status projections shown around the chat page.
 *
 * <p>The controller is confined to the JavaFX application thread except for tracker and embedding
 * callbacks, which are marshalled through {@link FxDispatcher}. Rebinding removes callbacks from
 * the previous workspace; {@link #close()} is idempotent and releases every listener and timer.</p>
 */
final class ChatStatusController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatStatusController.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final ApplicationKernel kernel;
    private final FxDispatcher fx;
    private final ChatHeaderController header;
    private final ChatModeController modeBar;
    private final SidebarController sidebar;
    private final Supplier<ChatSession> currentSession;

    private AgentConfig settings;
    private TokenTracker tracker;
    private AutoCloseable embeddingSubscription;
    private Timeline refreshClock;
    private boolean closed;

    ChatStatusController(
            ApplicationKernel kernel,
            FxDispatcher fx,
            ChatHeaderController header,
            ChatModeController modeBar,
            SidebarController sidebar,
            Supplier<ChatSession> currentSession) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.header = Objects.requireNonNull(header, "header");
        this.modeBar = Objects.requireNonNull(modeBar, "modeBar");
        this.sidebar = Objects.requireNonNull(sidebar, "sidebar");
        this.currentSession = Objects.requireNonNull(currentSession, "currentSession");
    }

    void start(WorkspaceRuntime workspace) {
        bind(workspace);
        refreshClock = new Timeline(new KeyFrame(Duration.seconds(10), event -> refresh()));
        refreshClock.setCycleCount(Animation.INDEFINITE);
        refreshClock.play();
    }

    void bind(WorkspaceRuntime workspace) {
        Objects.requireNonNull(workspace, "workspace");
        releaseWorkspaceListeners();
        settings = workspace.agentConfig();
        tracker = workspace.tokenTracker();
        tracker.setOnTokensChanged(() -> fx.dispatch(() -> {
            if (!closed) {
                refresh();
            }
        }));
        embeddingSubscription = workspace.embeddingGateway().addHealthListener(snapshot ->
                fx.dispatch(() -> {
                    if (!closed) {
                        header.showEmbedding(snapshot);
                    }
                }));
        refresh();
    }

    void resetSession() {
        if (tracker != null) {
            tracker.resetSession();
            refresh();
        }
    }

    void setStreaming(boolean streaming) {
        header.setStreaming(streaming);
    }

    String modelName() {
        return settings == null ? "" : Objects.requireNonNullElse(settings.getModelName(), "");
    }

    void refreshTitle() {
        ChatSession session = currentSession.get();
        if (session == null) {
            return;
        }
        long contextTokens = tracker == null ? 0 : tracker.getSessionTokens();
        String context = contextTokens >= 1000
                ? String.format("%.1fk", contextTokens / 1000.0)
                : Long.toString(contextTokens);
        String createdAt = session.getCreatedAt() == null
                ? "—" : session.getCreatedAt().format(TIME_FORMAT);
        String model = modelName();
        String profileInputLimit = "plan".equals(modeBar.selectedModeId()) ? "80k" : "120k";
        String metadata = session.getMessages().size() + " 条消息 · 创建于 " + createdAt
                + " · ctx " + context + " / " + profileInputLimit
                + (model.isBlank() ? "" : " · " + model);
        header.showTitle(session.getTitle(), metadata);
    }

    void refresh() {
        refreshLocalMode();
        refreshTokenSummary();
        refreshNavigationBadges();
        refreshTitle();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (refreshClock != null) {
            refreshClock.stop();
            refreshClock = null;
        }
        releaseWorkspaceListeners();
        settings = null;
    }

    private void refreshLocalMode() {
        header.setLocalMode(settings != null
                && "Ollama".equalsIgnoreCase(settings.getProviderType()));
    }

    private void refreshTokenSummary() {
        if (tracker == null) {
            return;
        }
        try {
            long sessionTokens = tracker.getSessionTokens();
            long todayTokens = tracker.getTodayTokens();
            long monthlyTokens = tracker.getMonthlyTokens();
            TokenTracker.DailyUsage today = tracker.getTodayUsage();
            TokenTracker.DailyUsage month = tracker.getMonthlyUsage();
            String hitRate = today.meteredInput <= 0 ? "—"
                    : String.format("%.1f%%", 100.0 * today.cachedInput / today.meteredInput);
            String summary = "今日 " + TokenTracker.formatTokens(todayTokens)
                    + " · 会话 " + TokenTracker.formatTokens(sessionTokens)
                    + " · 缓存命中 " + hitRate;
            String details = "今日累计：" + TokenTracker.formatTokens(todayTokens) + " tokens"
                    + "（输入 " + TokenTracker.formatTokens(today.input)
                    + " / 输出 " + TokenTracker.formatTokens(today.output)
                    + " / 缓存读 " + TokenTracker.formatTokens(today.cachedInput)
                    + " / 缓存写 " + TokenTracker.formatTokens(today.cacheWriteInput)
                    + " / 推理 " + reasoning(today.reasoning) + "）\n"
                    + "本月累计：" + TokenTracker.formatTokens(monthlyTokens) + " tokens"
                    + "（输入 " + TokenTracker.formatTokens(month.input)
                    + " / 输出 " + TokenTracker.formatTokens(month.output)
                    + " / 缓存读 " + TokenTracker.formatTokens(month.cachedInput)
                    + " / 缓存写 " + TokenTracker.formatTokens(month.cacheWriteInput)
                    + " / 推理 " + reasoning(month.reasoning) + "）\n"
                    + "今日缓存命中率：" + hitRate + "\n"
                    + "本次会话：" + TokenTracker.formatTokens(sessionTokens) + " tokens · 耗时 "
                    + TokenTracker.formatDuration(tracker.getSessionDurationSeconds()) + "\n"
                    + "点击可重置本次会话计数";
            modeBar.updateTokenSummary(summary, details);
        } catch (RuntimeException failure) {
            log.debug("刷新 Token 徽标失败", failure);
        }
    }

    private static String reasoning(long tokens) {
        return tokens <= 0 ? "—" : TokenTracker.formatTokens(tokens);
    }

    private void refreshNavigationBadges() {
        try {
            WorkspaceRuntime workspace = kernel.current();
            sidebar.updateSkillBadge(workspace.skills().pendingProposalCount());
            int activeTasks = (int) workspace.sddTasks().snapshot().tasks().stream()
                    .filter(task -> task.state() == SddTaskState.RUNNING
                            || task.state() == SddTaskState.NEEDS_HUMAN)
                    .count();
            sidebar.updateTaskBadge(activeTasks);
        } catch (RuntimeException failure) {
            log.debug("刷新侧边栏徽章失败", failure);
        }
    }

    private void releaseWorkspaceListeners() {
        if (tracker != null) {
            tracker.setOnTokensChanged(null);
            tracker = null;
        }
        AutoCloseable subscription = embeddingSubscription;
        embeddingSubscription = null;
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception failure) {
                log.debug("关闭嵌入健康订阅失败", failure);
            }
        }
    }
}
