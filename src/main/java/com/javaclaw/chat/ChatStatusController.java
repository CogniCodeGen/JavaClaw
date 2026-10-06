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
import java.util.HashMap;
import java.util.Map;
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
    private final Map<String, SessionUsage> sessionUsage = new HashMap<>();
    private String workspaceId;
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
        sessionUsage.clear();
        workspaceId = workspace.context().workspaceId();
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
        ChatSession session = currentSession.get();
        if (session != null) {
            SessionUsage usage = usageFor(session);
            usage.tokens = 0;
            usage.durationMs = 0;
            if (usage.turnStartedNanos != 0) usage.turnStartedNanos = System.nanoTime();
        }
        refreshUsage();
    }

    String workspaceId() {
        return workspaceId;
    }

    void beginTurn(String turnWorkspaceId, ChatSession session) {
        if (!owns(turnWorkspaceId, session)) return;
        usageFor(session).turnStartedNanos = System.nanoTime();
        refreshUsage();
    }

    void recordUsage(String turnWorkspaceId, ChatSession session, long input, long output) {
        if (!owns(turnWorkspaceId, session)) return;
        SessionUsage usage = usageFor(session);
        usage.tokens = add(usage.tokens, add(Math.max(0, input), Math.max(0, output)));
        refreshUsage();
    }

    void finishTurn(String turnWorkspaceId, ChatSession session) {
        if (!owns(turnWorkspaceId, session)) return;
        SessionUsage usage = usageFor(session);
        if (usage.turnStartedNanos != 0) {
            usage.durationMs = add(usage.durationMs, activeDurationMs(usage));
            usage.turnStartedNanos = 0;
        }
        refreshUsage();
    }

    void forgetSession(String sessionId) {
        sessionUsage.remove(sessionId);
    }

    void clearSessionUsage() {
        sessionUsage.clear();
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
        long contextTokens = usageFor(session).tokens;
        String context = contextTokens >= 1000
                ? String.format("%.1fk", contextTokens / 1000.0)
                : Long.toString(contextTokens);
        String createdAt = session.getCreatedAt() == null
                ? "—" : session.getCreatedAt().format(TIME_FORMAT);
        String model = modelName();
        String metadata = session.getMessages().size() + " 条消息 · 创建于 " + createdAt
                + " · ctx " + context + " / 200k"
                + (model.isBlank() ? "" : " · " + model);
        header.showTitle(session.getTitle(), metadata);
        refreshTokenSummary();
    }

    void refresh() {
        refreshLocalMode();
        refreshNavigationBadges();
        refreshUsage();
    }

    private void refreshUsage() {
        if (currentSession.get() == null) refreshTokenSummary();
        else refreshTitle();
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
        sessionUsage.clear();
        workspaceId = null;
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
            ChatSession session = currentSession.get();
            SessionUsage sessionProjection = session == null ? null : usageFor(session);
            long sessionTokens = sessionProjection == null ? 0 : sessionProjection.tokens;
            long todayTokens = tracker.getTodayTokens();
            long monthlyTokens = tracker.getMonthlyTokens();
            String monthlyCost = TokenTracker.formatCostCny(tracker.getMonthlyCostCny());
            TokenTracker.DailyUsage today = tracker.getTodayUsage();
            TokenTracker.DailyUsage month = tracker.getMonthlyUsage();
            String summary = "今日 " + TokenTracker.formatTokens(todayTokens)
                    + " · 会话 " + TokenTracker.formatTokens(sessionTokens)
                    + " · " + monthlyCost;
            String details = "今日累计：" + TokenTracker.formatTokens(todayTokens) + " tokens"
                    + "（输入 " + TokenTracker.formatTokens(today.input)
                    + " / 输出 " + TokenTracker.formatTokens(today.output) + "）\n"
                    + "本月累计：" + TokenTracker.formatTokens(monthlyTokens) + " tokens"
                    + "（输入 " + TokenTracker.formatTokens(month.input)
                    + " / 输出 " + TokenTracker.formatTokens(month.output) + "）\n"
                    + "本月成本：" + monthlyCost + "（估算，仅供参考）\n"
                    + "本次会话：" + TokenTracker.formatTokens(sessionTokens) + " tokens · 耗时 "
                    + TokenTracker.formatDuration(sessionProjection == null ? 0
                            : add(sessionProjection.durationMs, activeDurationMs(sessionProjection)) / 1000) + "\n"
                    + "点击可重置本次会话计数";
            modeBar.updateTokenSummary(summary, details);
        } catch (RuntimeException failure) {
            log.debug("刷新 Token 徽标失败", failure);
        }
    }

    private boolean owns(String turnWorkspaceId, ChatSession session) {
        return !closed && session != null && workspaceId != null
                && workspaceId.equals(turnWorkspaceId);
    }

    private SessionUsage usageFor(ChatSession session) {
        return sessionUsage.computeIfAbsent(session.getId(), ignored -> {
            SessionUsage usage = new SessionUsage();
            // Seed once. Live deltas already include the metrics later saved on the final reply.
            for (ChatMessage message : session.getMessages()) {
                TurnMetrics metrics = message.getMetrics();
                if (metrics == null) continue;
                usage.tokens = add(usage.tokens, add(metrics.inputTokens(), metrics.outputTokens()));
                usage.durationMs = add(usage.durationMs, metrics.durationMs());
            }
            return usage;
        });
    }

    private static long activeDurationMs(SessionUsage usage) {
        return usage.turnStartedNanos == 0 ? 0
                : Math.max(0, (System.nanoTime() - usage.turnStartedNanos) / 1_000_000);
    }

    private static long add(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static final class SessionUsage {
        long tokens;
        long durationMs;
        long turnStartedNanos;
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
