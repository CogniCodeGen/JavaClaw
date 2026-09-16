package com.javaclaw.server.turn;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserCommands.ContinuationFailure;
import com.javaclaw.builtin.contracts.BrowserCommands.ContinuationStatus;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.runtime.TurnExecutionResult;
import com.javaclaw.server.extension.SiteBrowserHostContext;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;

/**
 * Site 来源授权的续接编排；只有旧 Turn 已提交成功终态才能消费剩余预算创建新 Turn。
 *
 * <p>Core 幂等回执先于预算及配置重验：崩溃前已创建的后继只能恢复原版本，不能再创建或扩大预算。 请求、后继关联及失败结果持久记录，并通过扩展事件报告；失败不在下次启动时静默重试。
 */
public final class BrowserTurnContinuation {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserTurnContinuation.class);
    private static final String COLLECTION = "browser.continuations";
    private static final ExtensionId SITE = new ExtensionId(BuiltinExtensionIds.SITE);
    private static final Object[] LOCKS = locks();
    private final CoreCommandService core;
    private final CanonicalJson json;
    private final Clock clock;
    private final H2ManagedExtensionStore store;
    private volatile TurnDispatcher dispatcher;
    private Function<TurnId, TurnExecutionResult> terminal;

    /**
     * 绑定唯一宿主数据库和 Core 编排边界。
     *
     * @param host 已组合的宿主依赖
     */
    public BrowserTurnContinuation(SiteBrowserHostContext host) {
        this(host.database(), host.core(), host.json(), host.clock());
    }

    BrowserTurnContinuation(H2Database database, CoreCommandService core, CanonicalJson json, Clock clock) {
        this.core = Objects.requireNonNull(core, "core");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        store = new H2ManagedExtensionStore(database, clock);
    }

    /**
     * 仅受信 Site 工具的控制结果能结束当前模型循环。
     *
     * @param json 规范 codec
     * @param tool 冻结完整工具身份
     * @param result 工具实际输出
     * @return 是否请求在持久工具结果后续接
     */
    public static boolean matches(CanonicalJson json, ToolIdentity tool, CanonicalPayload result) {
        return BuiltinExtensionIds.SITE.equals(tool.producerId())
                && BrowserCommands.TOOL_NAMES.contains(tool.name())
                && json.textField(result, "status")
                        .filter("BROWSER_AUTHORIZATION_CONTINUATION"::equals)
                        .isPresent();
    }

    /**
     * 持久记录已批准来源；本记录不授权当前 Turn，也不承诺预算足够续接。
     *
     * @param parent 当前执行 Turn
     * @param origin 已批准的精确 HTTPS 来源
     */
    public void request(TurnId parent, URI origin) {
        URI checked = BrowserGrantContracts.normalizeOrigin(origin);
        AgentTurn turn = core.findTurn(parent).orElseThrow();
        if (turn.status() != TurnStatus.RUNNING && turn.status() != TurnStatus.WAITING) {
            throw new IllegalStateException("仅活动 Turn 可以请求浏览器授权续接");
        }
        synchronized (lock(parent)) {
            if (pending(parent).isEmpty()) {
                save(new Pending(
                        parent,
                        checked,
                        Optional.empty(),
                        State.REQUESTED,
                        ContinuationFailure.NONE,
                        "已确认来源，等待当前 Turn 结束后检查剩余预算"));
            }
        }
    }

    /**
     * 查询当前 Turn 是否有已持久化、尚未创建后继的有效续接请求。
     *
     * @param parent 当前 Turn
     * @return 仅 REQUESTED 且没有后继时为真，不改变工具结果或重放副作用
     */
    public boolean requested(TurnId parent) {
        return pending(Objects.requireNonNull(parent, "parent"))
                .filter(value -> value.state() == State.REQUESTED)
                .filter(value -> value.child().isEmpty())
                .isPresent();
    }

    /**
     * 在组合根完成 Dispatcher 装配后绑定一次；持久恢复必须等其他端口全部就绪。
     *
     * @param value 当前宿主调度器
     */
    public void bind(HarnessTurnDispatcher value) {
        bind(value, value::terminalResult);
    }

    synchronized void bind(TurnDispatcher value, Function<TurnId, TurnExecutionResult> recovery) {
        if (dispatcher != null) {
            throw new IllegalStateException("浏览器续接调度器已绑定");
        }
        terminal = Objects.requireNonNull(recovery, "recovery");
        dispatcher = Objects.requireNonNull(value, "dispatcher");
    }

    /**
     * 在旧 Turn 释放运行租约后尝试续接；取消、失败、预算耗尽或配置不可用都会报告持久状态。
     *
     * @param result 已持久终态及真实累计预算
     */
    public void finished(TurnExecutionResult result) {
        synchronized (lock(result.turnId())) {
            Optional<Pending> found = pending(result.turnId());
            if (found.isEmpty() || found.orElseThrow().stopped()) {
                return;
            }
            Pending request = found.orElseThrow();
            AgentTurn parent = core.findTurn(request.parent()).orElseThrow();
            if (result.status() != TurnStatus.COMPLETED || parent.status() != TurnStatus.COMPLETED) {
                save(request.change(
                        request.child(),
                        State.STOPPED,
                        ContinuationFailure.PARENT_NOT_COMPLETED,
                        "原任务已取消或失败，没有创建续接任务"));
                return;
            }
            try {
                continueTurn(parent, result, request);
            } catch (BudgetExhausted exhausted) {
                save(request.change(request.child(), State.FAILED, exhausted.reason, exhausted.getMessage()));
            } catch (RuntimeException failure) {
                save(request.change(
                        committedChild(request),
                        State.FAILED,
                        ContinuationFailure.CONFIGURATION_UNAVAILABLE,
                        "新任务配置不可用或执行受阻；来源授权已保留，请检查后手动继续"));
                LOGGER.warn(
                        "浏览器授权续接 {} 未执行：{}", result.turnId(), failure.getClass().getSimpleName());
            }
        }
    }

    private void continueTurn(AgentTurn parent, TurnExecutionResult result, Pending pending) {
        CommandIdentity identity = identity(parent.id(), pending.origin());
        // 已提交的后继先恢复；过期墙钟、模型撤销不能使我们丢失崩溃前已经提交的身份。
        AgentTurn child = core.recoverTurnStart(identity).orElseGet(() -> create(parent, result, identity));
        Pending linked =
                pending.change(Optional.of(child.id()), State.CREATED, ContinuationFailure.NONE, "已创建续接任务，正在恢复执行");
        save(linked);
        AgentTurn current = core.findTurn(child.id()).orElseThrow();
        if (current.status() == TurnStatus.QUEUED || current.status() == TurnStatus.RUNNING) {
            dispatcher.resume(child.id());
        }
        AgentTurn after = core.findTurn(child.id()).orElseThrow();
        if (after.status() == TurnStatus.FAILED || after.status() == TurnStatus.CANCELLED) {
            save(linked.change(
                    linked.child(),
                    State.FAILED,
                    ContinuationFailure.CONFIGURATION_UNAVAILABLE,
                    "续接任务未能执行或已取消；请检查配置后手动继续"));
        } else {
            save(linked.change(linked.child(), State.STARTED, ContinuationFailure.NONE, "已续接原任务，聊天记录继续使用同一条用户消息"));
        }
    }

    private AgentTurn create(AgentTurn parent, TurnExecutionResult result, CommandIdentity identity) {
        if (core.parentTurn(parent.threadId()).isPresent()) {
            throw new BudgetExhausted(
                    ContinuationFailure.CHILD_BUDGET_UNAVAILABLE, "子任务的父预算预留不能自动续接；来源授权已保留，请由父任务重新安排");
        }
        TurnBudget budget = remaining(parent, result);
        var prior = AgentConfigurationResolver.overrides(core.resolvedConfig(parent.id()));
        var execution = new ExecutionOverrides(
                prior.role(),
                prior.provider(),
                prior.permissionProfile(),
                prior.approvalPolicy(),
                Optional.of(budget),
                prior.visibleCapabilities(),
                prior.reasoning());
        var message = core.turnUserMessage(parent.id());
        var payload = new CoreRpcContracts.TurnStartPayload(parent.threadId(), execution, message.text());
        return core.startTurn(identity, dispatcher.resolve(payload, message).withContinuedFrom(parent.id()));
    }

    private Optional<TurnId> committedChild(Pending pending) {
        return pending.child()
                .or(() -> core.recoverTurnStart(identity(pending.parent(), pending.origin()))
                        .map(AgentTurn::id));
    }

    private TurnBudget remaining(AgentTurn parent, TurnExecutionResult result) {
        TurnBudget limit = parent.budget();
        long input = limit.inputTokens() - result.usage().inputTokens();
        long output = limit.outputTokens() - result.usage().generatedTokens();
        int tools = limit.toolCalls() - result.toolCalls();
        Duration elapsed = Duration.between(parent.createdAt(), clock.instant());
        Duration wall = limit.wallTime().minus(elapsed.isNegative() ? Duration.ZERO : elapsed);
        if (input < 1 || output < 1 || tools < 1 || wall.isNegative() || wall.isZero()) {
            throw new BudgetExhausted(ContinuationFailure.BUDGET_EXHAUSTED, "原任务剩余预算不足；浏览器授权已保留，请手动继续任务");
        }
        return new TurnBudget(input, output, tools, 0, wall);
    }

    private CommandIdentity identity(TurnId parent, URI origin) {
        String digest = json.encode(Map.of("parent", parent, "origin", origin)).sha256();
        return new CommandIdentity("turn/start", "browser-continue:" + parent, 0, digest);
    }

    private Optional<Pending> pending(TurnId parent) {
        return transaction(() -> store.inTransaction(
                SITE,
                tx -> tx.get(COLLECTION, parent.toString()).map(value -> json.decode(value.payload(), Pending.class))));
    }

    private void save(Pending pending) {
        AgentTurn parent = core.findTurn(pending.parent()).orElseThrow();
        var workspace = core.workspaceForThread(parent.threadId()).id();
        transaction(() -> store.inTransaction(SITE, tx -> {
            var current = tx.get(COLLECTION, parent.id().toString());
            if (current.isPresent() && current.orElseThrow().payload().equals(json.encode(pending))) {
                return null;
            }
            tx.put(
                    COLLECTION,
                    parent.id().toString(),
                    current.map(value -> value.revision()).orElse(0L),
                    json.encode(pending));
            String collection = "browser.continuation-status." + workspace;
            var projection = tx.get(collection, parent.threadId().toString());
            tx.put(
                    collection,
                    parent.threadId().toString(),
                    projection.map(value -> value.revision()).orElse(0L),
                    json.encode(new ContinuationStatus(
                            BrowserCommands.ContinuationState.valueOf(
                                    pending.state().name()),
                            pending.reason(),
                            pending.detail(),
                            parent.id(),
                            pending.child())));
            tx.appendEvent(
                    "site.browser.continuation.changed",
                    json.encode(Map.of(
                            "workspaceId",
                            workspace,
                            "threadId",
                            parent.threadId(),
                            "parentTurnId",
                            parent.id(),
                            "childTurnId",
                            pending.child(),
                            "state",
                            pending.state(),
                            "detail",
                            pending.detail(),
                            "reason",
                            pending.reason())));
            return null;
        }));
    }

    /**
     * 所有运行端口装配完成后恢复持久请求；已启动或明确失败的记录不自动重试。
     *
     * <p>必须先绑定 Dispatcher，再恢复现有活动 Turn，最后调用本方法，避免配置解析使用未就绪端口。
     */
    public void recoverPending() {
        if (dispatcher == null) {
            throw new IllegalStateException("浏览器续接调度器尚未绑定");
        }
        String cursor = "";
        while (true) {
            String after = cursor;
            var rows = transaction(() -> store.inTransaction(SITE, tx -> tx.list(COLLECTION, after, 100)));
            if (rows.isEmpty()) {
                return;
            }
            for (var row : rows) {
                Pending pending = json.decode(row.payload(), Pending.class);
                if (!pending.stopped()) {
                    core.findTurn(pending.parent())
                            .filter(turn -> terminal(turn.status()))
                            .ifPresent(turn -> finished(terminal.apply(turn.id())));
                }
            }
            cursor = rows.getLast().key();
        }
    }

    private static boolean terminal(TurnStatus status) {
        return status == TurnStatus.COMPLETED || status == TurnStatus.FAILED || status == TurnStatus.CANCELLED;
    }

    private static Object[] locks() {
        Object[] locks = new Object[64];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private static Object lock(TurnId parent) {
        return LOCKS[Math.floorMod(parent.hashCode(), LOCKS.length)];
    }

    private static <T> T transaction(java.util.concurrent.Callable<T> work) {
        try {
            return work.call();
        } catch (Exception failure) {
            throw new IllegalStateException("浏览器续接记录无法访问", failure);
        }
    }

    private enum State {
        REQUESTED,
        CREATED,
        STARTED,
        FAILED,
        STOPPED
    }

    private record Pending(
            TurnId parent, URI origin, Optional<TurnId> child, State state, ContinuationFailure reason, String detail) {
        private Pending {
            state = state == null ? State.REQUESTED : state;
            reason = reason == null ? ContinuationFailure.NONE : reason;
            detail = detail == null ? "已请求续接" : detail;
        }

        boolean stopped() {
            return state == State.FAILED || state == State.STOPPED || state == State.STARTED;
        }

        Pending change(Optional<TurnId> next, State status, ContinuationFailure failure, String explanation) {
            return new Pending(parent, origin, next, status, failure, explanation);
        }
    }

    private static final class BudgetExhausted extends IllegalStateException {
        private final ContinuationFailure reason;

        private BudgetExhausted(ContinuationFailure reason, String message) {
            super(message);
            this.reason = reason;
        }
    }
}
