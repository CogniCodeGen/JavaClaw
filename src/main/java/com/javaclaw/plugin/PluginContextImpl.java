package com.javaclaw.plugin;

import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.PluginConfig;
import com.javaclaw.plugin.api.PluginContext;
import com.javaclaw.plugin.api.PluginException;
import com.javaclaw.plugin.api.capability.ChatAccess;
import com.javaclaw.plugin.api.capability.MemoryAccess;
import com.javaclaw.plugin.api.capability.ScheduleAccess;
import com.javaclaw.plugin.api.capability.StorageAccess;
import com.javaclaw.plugin.api.exec.PluginExecutor;

/**
 * 能力网关实现 —— 按插件已授权能力装配可用句柄。未授权能力对应 getter 抛
 * {@link PluginException.CapabilityNotGrantedException}（未装配即为 null）。
 *
 * @author JavaClaw
 */
final class PluginContextImpl implements PluginContext {

    private final String pluginId;
    private final PluginExecutor executor;
    private final PluginConfig config;

    // —— 各能力句柄；未授权时为 null ——
    private final ChatAccess chat;
    private final ScheduleAccess schedule;
    private final MemoryAccess memory;
    private final StorageAccess storage;

    PluginContextImpl(String pluginId, PluginExecutor executor, PluginConfig config,
                      ChatAccess chat, ScheduleAccess schedule,
                      MemoryAccess memory, StorageAccess storage) {
        this.pluginId = pluginId;
        this.executor = executor;
        this.config = config;
        this.chat = chat;
        this.schedule = schedule;
        this.memory = memory;
        this.storage = storage;
    }

    @Override
    public PluginExecutor exec() {
        return executor;
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    @Override
    public ChatAccess chat() {
        return require(chat, Capability.CHAT);
    }

    @Override
    public ScheduleAccess schedule() {
        return require(schedule, Capability.SCHEDULE);
    }

    @Override
    public MemoryAccess memory() {
        return require(memory, Capability.MEMORY);
    }

    @Override
    public StorageAccess storage() {
        return require(storage, Capability.STORAGE);
    }

    private <T> T require(T handle, Capability capability) {
        if (handle == null) {
            throw new PluginException.CapabilityNotGrantedException(pluginId, capability);
        }
        return handle;
    }
}
