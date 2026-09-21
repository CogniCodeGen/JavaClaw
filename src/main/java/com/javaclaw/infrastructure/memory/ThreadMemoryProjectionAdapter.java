package com.javaclaw.infrastructure.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ThreadLifecycleListener;
import com.javaclaw.framework.spi.ThreadProjectionListener;
import com.javaclaw.memory.MemoryGraphScope;
import com.javaclaw.memory.MemoryService;

import java.util.List;
import java.util.Objects;

/** Projects committed terminal events. Acknowledgement requires a durable, idempotent raw episode. */
public final class ThreadMemoryProjectionAdapter implements ThreadProjectionListener, ThreadLifecycleListener {
    private final MemoryService memory;
    private final ObjectMapper json;

    public ThreadMemoryProjectionAdapter(MemoryService memory, ObjectMapper json) {
        this.memory = Objects.requireNonNull(memory);
        this.json = Objects.requireNonNull(json);
        memory.onHistoryRecovery((thread, history) -> MemoryForkReplayer.replay(memory, json, this, thread, history),
                (thread, history) -> MemoryForkReplayer.catchup(memory, json, this, thread, history));
    }

    @Override public boolean accepts(RunScope scope) {
        MemoryGraphScope owner = memory.defaultScope();
        return owner != null && owner.workspaceId().equals(scope.workspaceId())
                && owner.userId().equals(scope.userId());
    }

    @Override public void project(RunRequest request, ThreadEvent event) {
        if (!accepts(event.scope())) throw new IllegalArgumentException("跨工作区记忆投影");
        if (!event.scope().equals(request.scope())) throw new IllegalArgumentException("记忆投影与源 Turn 范围不一致");
        if (!java.util.Set.of("turn/completed", "turn/failed", "turn/cancelled").contains(event.type()) || event.turnId() == null
                || request.source().kind().equals("maintenance")) return;
        String userInput = request.inputs().stream().filter(block -> block.type().equals("core.text"))
                .map(block -> block.data().path("text").asText(""))
                .collect(java.util.stream.Collectors.joining("\n"));
        var output = event.payload().path("event").path("payload").path("output");
        String reply = output.isTextual() ? output.asText() : output.path("text").asText("");
        if (reply.isBlank() && !output.isMissingNode() && !output.isNull()) reply = output.toString();
        String trace = request.attributes().containsKey("framework.toolTrace")
                ? request.attributes().get("framework.toolTrace").toString() : null;
        String originThread = request.attributes().containsKey("memory.originThreadId")
                ? request.attributes().get("memory.originThreadId").asText()
                : request.scope().sessionId();
        String originTurn = request.attributes().containsKey("memory.originTurnId")
                ? request.attributes().get("memory.originTurnId").asText() : event.turnId().value();
        // Historical copied turns retain their original evidence identities and are not new habit evidence.
        boolean habits = event.type().equals("turn/completed") && event.scope().sessionId().equals(originThread)
                && java.util.Set.of("chat", "plan").contains(request.source().kind());
        if (habits) memory.rememberExplicitPreference(MemoryGraphScope.thread(event.scope()), event.turnId().value(), userInput);
        String status = event.type().substring("turn/".length());
        if (!status.equals("completed")) reply = "[本轮状态：" + status + "，输出未经确认] " + reply;
        memory.rememberTerminal(MemoryGraphScope.thread(event.scope()), event.turnId().runId(),
                event.turnId().value(), event.sequence(), userInput, reply, trace, habits,
                originThread, originTurn, status);
    }

    @Override public void forked(ThreadSnapshot target, List<ThreadEvent> history) {
        if (!accepts(target.scope())) throw new IllegalArgumentException("跨工作区图谱分支");
        MemoryForkReplayer.replay(memory, json, this, target, history);
    }

    @Override public void deleting(RunScope scope) {
        if (!accepts(scope)) throw new IllegalArgumentException("跨工作区图谱删除");
        memory.deleteThread(MemoryGraphScope.thread(scope));
    }
}
