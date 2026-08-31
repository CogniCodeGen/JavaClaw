package com.javaclaw.agent.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.javaclaw.agent.runtime.ThreadUseCases;
import com.javaclaw.agent.runtime.TurnUseCases;
import com.javaclaw.agent.runtime.WorkspaceUseCases;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 持久收件箱驱动的低优先级维护调度；全进程最多一个维护 Turn，等待前台空闲，不创建新的执行内核。 */
public final class KnowledgeMaintenanceScheduler implements AutoCloseable {
    private final KnowledgeMaintenanceRepository jobs;
    private final LearningRepository preferences;
    private final ThreadUseCases threads;
    private final TurnUseCases turns;
    private final WorkspaceUseCases workspaces;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("javaclaw-knowledge-maintenance").factory());
    private boolean started;
    private boolean closed;

    /** 注入窄用例与持久收件箱；构造不启动后台线程，测试可显式调用 tick。 */
    public KnowledgeMaintenanceScheduler(
            KnowledgeMaintenanceRepository jobs,
            LearningRepository preferences,
            ThreadUseCases threads,
            TurnUseCases turns,
            WorkspaceUseCases workspaces) {
        this.jobs = java.util.Objects.requireNonNull(jobs);
        this.preferences = java.util.Objects.requireNonNull(preferences);
        this.threads = java.util.Objects.requireNonNull(threads);
        this.turns = java.util.Objects.requireNonNull(turns);
        this.workspaces = java.util.Objects.requireNonNull(workspaces);
    }

    /** 每两秒非阻塞检查持久待办；不会因网络或模型速度阻塞主 Turn 线程。 */
    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException("maintenance scheduler is not startable");
        }
        started = true;
        timer.scheduleWithFixedDelay(
                () -> {
                    try {
                        tick();
                    } catch (RuntimeException failure) {
                        // 持久任务保留供下次核对，禁止把含私密内容的异常信息写入日志。
                        System.getLogger(getClass().getName())
                                .log(
                                        System.Logger.Level.WARNING,
                                        "Knowledge maintenance polling failed: "
                                                + failure.getClass().getSimpleName());
                    }
                },
                2,
                2,
                TimeUnit.SECONDS);
    }

    /** 一次有界调度；已启动的模型失败/中断只记录终态，不自动重发潜在已收费调用。 */
    public synchronized void tick() {
        if (closed) {
            return;
        }
        jobs.discover();
        var pending = jobs.pending(50);
        // 先核对所有在途任务，防止排在前面的新任务掩盖一个较早启动的模型请求。
        for (var job : pending) {
            if (job.maintenanceThreadId() != null) {
                var turn = threads.readTurnByIdempotencyKey(new ThreadId(job.maintenanceThreadId()), key(job));
                if (turn.isPresent()) {
                    if (!turn.get().status().terminal()) {
                        return;
                    }
                    jobs.finish(job.sourceTurnId(), turn.get().status().name(), "维护 Turn 已进入终态");
                }
            }
        }
        for (var job : jobs.pending(50)) {
            if (jobs.foregroundActive(job.workspaceId())) {
                continue;
            }
            var settings = preferences.learningSettings(job.workspaceId());
            var source = threads.readTurn(new TurnId(job.sourceTurnId())).orElse(null);
            if (source == null
                    || (!settings.memoryAutomatic() && "OFF".equals(settings.skillMode()))
                    || jobs.evidence(job.sourceTurnId(), job.workspaceId()).isEmpty()) {
                jobs.finish(job.sourceTurnId(), "SKIPPED", "没有已授权且可用的学习来源");
                continue;
            }
            var workspace =
                    workspaces.readWorkspace(new WorkspaceId(job.workspaceId())).orElseThrow();
            if (workspace.locked()) {
                continue;
            }
            var id = job.maintenanceThreadId() == null
                    ? threads.startThread(workspace.id(), "知识维护 · " + job.sourceTurnId(), "thread-" + key(job))
                            .id()
                    : new ThreadId(job.maintenanceThreadId());
            jobs.started(job.sourceTurnId(), id.value());
            var base = source.config();
            var attributes = new LinkedHashMap<String, String>();
            // 单独的维护用途不复制人设、工具集合或外部历史；限额不超过原 Profile，也不允许无限继承。
            attributes.put("profileKind", "SCHEDULE");
            attributes.put("invocationPurpose", "KNOWLEDGE_MAINTENANCE");
            attributes.put("maintenanceSourceTurn", source.id().value());
            attributes.put("maxModelCalls", bounded(base, "maxModelCalls", 4));
            attributes.put("maxTokens", bounded(base, "maxTokens", 32_000));
            attributes.put("maxDurationSeconds", bounded(base, "maxDurationSeconds", 60));
            attributes.put("maxOutputTokens", "3000");
            var config = new TurnConfig(
                    base.model(),
                    base.provider(),
                    "low",
                    workspace.root(),
                    SandboxPolicy.readOnly(Set.of(), base.sandboxPolicy().protectedRoots()),
                    ApprovalPolicy.NEVER,
                    Set.of(),
                    attributes);
            try {
                turns.startTurn(new TurnStartCommand(
                        id, List.of(new TurnInput.Text("对已完成任务进行有证据、有限预算的知识维护。")), config, key(job)));
            } catch (IllegalStateException failure) {
                // 并发前台请求可能刚占满配额；只有尚未创建 Turn 的任务可以稍后重新尝试。
                if (threads.readTurnByIdempotencyKey(id, key(job)).isPresent()) {
                    throw failure;
                }
            }
            return;
        }
    }

    private static String key(KnowledgeMaintenanceRepository.Job job) {
        return "knowledge-maintenance-" + job.sourceTurnId();
    }

    private static String bounded(TurnConfig config, String name, int ceiling) {
        int configured = Integer.parseInt(config.attributes().getOrDefault(name, "0"));
        return Integer.toString(configured > 0 ? Math.min(configured, ceiling) : ceiling);
    }

    @Override
    public synchronized void close() {
        closed = true;
        timer.shutdownNow();
    }
}
