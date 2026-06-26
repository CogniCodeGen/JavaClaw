package com.javaclaw.plugin.capability;

import com.javaclaw.agent.memory.MemoryManager;
import com.javaclaw.plugin.CapabilityGuard;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.capability.MemoryAccess;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * MEMORY 能力实现（只读）—— 背靠宿主 {@link MemoryManager}，把内部 {@link Msg} 转为可序列化的
 * {@link MemoryMessage} 只读视图，不暴露任何写/清空入口。
 *
 * @author JavaClaw
 */
public final class MemoryAccessImpl implements MemoryAccess {

    private static final Logger log = LoggerFactory.getLogger(MemoryAccessImpl.class);

    private final String pluginId;
    private final MemoryManager memoryManager;

    public MemoryAccessImpl(String pluginId, MemoryManager memoryManager) {
        this.pluginId = pluginId;
        this.memoryManager = memoryManager;
    }

    @Override
    public List<String> listAgents() {
        CapabilityGuard.require(Capability.MEMORY);
        return memoryManager.getSnapshotAgentNames();
    }

    @Override
    public List<MemoryMessage> snapshot(String agentName) {
        CapabilityGuard.require(Capability.MEMORY);
        List<Msg> snapshot = memoryManager.getSnapshot(agentName);
        log.debug("插件[{}]读取智能体[{}]记忆快照，{} 条", pluginId, agentName, snapshot.size());
        return snapshot.stream()
                .map(m -> new MemoryMessage(roleName(m.getRole()), m.getName(), m.getTextContent()))
                .toList();
    }

    private String roleName(MsgRole role) {
        return role == null ? "" : role.name().toLowerCase(Locale.ROOT);
    }
}
