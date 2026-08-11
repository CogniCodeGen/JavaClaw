package com.javaclaw.system;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.process.ProcessRunner;

import java.util.Objects;

/**
 * 命令工具的进程级装配工厂。
 *
 * <p>工厂本身无可变会话状态；每次按调用来源创建新的工具门面，但共享 Spring 管理的白名单、
 * 交互式 Shell 会话和进程执行引擎。来源令牌在构造时固化，不允许运行中改写。</p>
 */
public final class CommandToolFactory {

    private final AgentConfig settings;
    private final CommandWhitelistManager whitelist;
    private final CommandSessionManager sessions;
    private final ProcessRunner processes;

    public CommandToolFactory(
            AgentConfig settings,
            CommandWhitelistManager whitelist,
            CommandSessionManager sessions,
            ProcessRunner processes) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.whitelist = Objects.requireNonNull(whitelist, "whitelist");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.processes = Objects.requireNonNull(processes, "processes");
    }

    public CommandLineTools create(ToolCallOrigin origin) {
        return new CommandLineTools(origin, settings, whitelist, sessions, processes);
    }
}
