package com.javaclaw.plugin.capability;

import com.javaclaw.memory.MemoryService;
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
    private static final String WORKSPACE_MEMORY = "workspace";

    private final String pluginId;
    private final MemoryService memory;

    public MemoryAccessImpl(String pluginId, MemoryService memory) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    @Override
    public List<String> listAgents() {
        CapabilityGuard.require(Capability.MEMORY);
        return memory.episodes().isEmpty() ? List.of() : List.of(WORKSPACE_MEMORY);
    }

    @Override
    public List<MemoryMessage> snapshot(String agentName) {
        CapabilityGuard.require(Capability.MEMORY);
        if (!WORKSPACE_MEMORY.equals(agentName)) return List.of();
        List<MemoryMessage> result = new ArrayList<>();
        for (var episode : memory.episodes()) {
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
