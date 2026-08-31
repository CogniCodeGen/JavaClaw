package com.javaclaw.agent.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.runtime.ThreadUseCases;
import com.javaclaw.agent.runtime.TurnUseCases;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;

/** 为显式压缩创建正常独立 Turn；Provider、模型、取消和预算继承最近一次真实会话配置。 */
public final class CompactionService implements CompactionUseCases {
    private final ThreadUseCases threads;
    private final TurnUseCases turns;

    /** 绑定 Thread 快照和 Turn 调度端口；客户端不能提交摘要、模型或权限配置。 */
    public CompactionService(ThreadUseCases threads, TurnUseCases turns) {
        this.threads = Objects.requireNonNull(threads, "threads");
        this.turns = Objects.requireNonNull(turns, "turns");
    }

    @Override
    public void start(ThreadId threadId) {
        var snapshot = threads.readThread(threadId).orElseThrow(() -> new NoSuchElementException("Thread not found"));
        if (snapshot.turns().stream().anyMatch(turn -> turn.completedAt() == null)) {
            throw new IllegalStateException("interrupt or finish the active Turn before compaction");
        }
        if (snapshot.items().isEmpty()) {
            throw new IllegalArgumentException("Thread has no history to compact");
        }
        var source = snapshot.turns().reversed().stream()
                .filter(turn -> !"COMPACTION".equals(turn.config().attributes().get("invocationPurpose")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Thread has no model configuration to compact"));
        String sourceHash = CompactionTranscript.fingerprint(snapshot.items());
        LinkedHashMap<String, String> attributes =
                new LinkedHashMap<>(source.config().attributes());
        attributes
                .keySet()
                .removeAll(Set.of(
                        "automationDefinition",
                        "automationExecutionId",
                        "adoptedPlanItemId",
                        "adoptedPlanHash",
                        "promptRequestHash"));
        attributes.put("invocationPurpose", "COMPACTION");
        attributes.put("compactionSourceHash", sourceHash);
        attributes.put("compactionBaseSequence", Long.toString(snapshot.thread().lastSequence()));
        attributes.put("maxModelCalls", "24");
        attributes.putIfAbsent("maxTokens", "200000");
        attributes.putIfAbsent("maxDurationSeconds", "300");
        attributes.putIfAbsent("maxOutputTokens", "4096");
        TurnConfig config = new TurnConfig(
                source.config().model(),
                source.config().provider(),
                source.config().reasoningEffort(),
                snapshot.thread().workingDirectory(),
                source.config().sandboxPolicy(),
                ApprovalPolicy.NEVER,
                Set.of(),
                attributes);
        String idempotencyKey = "manual-compaction-" + PromptHashes.sha256(sourceHash);
        turns.startTurn(new TurnStartCommand(
                threadId, List.of(new TurnInput.Text("执行上下文压缩。此内部输入不写入对话 transcript。")), config, idempotencyKey));
    }
}
