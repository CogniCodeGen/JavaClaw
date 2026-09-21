package com.javaclaw.plugin.capability;

import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.MemoryGraphScope;
import com.javaclaw.plugin.CapabilityGuard;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.capability.MemoryAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only plugin projection over durable workspace episodes, never over Agent internals. */
public final class MemoryAccessImpl implements MemoryAccess {
    private static final Logger log = LoggerFactory.getLogger(MemoryAccessImpl.class);

    private final String pluginId;
    private final MemoryService memory;

    public MemoryAccessImpl(String pluginId, MemoryService memory) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    @Override
    public List<String> listAgents() {
        CapabilityGuard.require(Capability.MEMORY);
        return memory.scopes().stream().filter(scope -> scope.kind() == MemoryGraphScope.Kind.THREAD)
                .map(MemoryGraphScope::threadId).toList();
    }

    @Override
    public List<MemoryMessage> snapshot(String agentName) {
        CapabilityGuard.require(Capability.MEMORY);
        MemoryGraphScope selected = memory.scopes().stream()
                .filter(scope -> scope.kind() == MemoryGraphScope.Kind.THREAD && scope.threadId().equals(agentName))
                .findFirst().orElse(null);
        return selected == null ? List.of() : read(selected);
    }

    @Override
    public List<GraphScope> listGraphs() {
        CapabilityGuard.require(Capability.MEMORY);
        return memory.scopes().stream().filter(scope -> scope.kind() == MemoryGraphScope.Kind.THREAD)
                .map(s -> new GraphScope(s.workspaceId(), s.userId(), s.threadId(), s.kind().name())).toList();
    }

    @Override
    public List<MemoryMessage> snapshot(GraphScope requested) {
        CapabilityGuard.require(Capability.MEMORY);
        if (requested == null || !listGraphs().contains(requested)) return List.of();
        return read(new MemoryGraphScope(requested.workspaceId(), requested.userId(),
                requested.threadId(), MemoryGraphScope.Kind.THREAD));
    }

    private List<MemoryMessage> read(MemoryGraphScope scope) {
        List<MemoryMessage> result = new ArrayList<>();
        for (var episode : memory.inScope(scope).episodes()) {
            if (episode.userInput != null) {
                result.add(new MemoryMessage("user", "user", episode.userInput));
            }
            if (episode.assistantReply != null) {
                result.add(new MemoryMessage("assistant", "JavaClaw", episode.assistantReply));
            }
        }
        log.debug("插件[{}]读取 durable 记忆快照，{} 条", pluginId, result.size());
        return List.copyOf(result);
    }
}
