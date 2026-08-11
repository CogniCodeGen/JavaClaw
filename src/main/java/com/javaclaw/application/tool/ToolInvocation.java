package com.javaclaw.application.tool;

import com.javaclaw.agent.ToolCallOrigin;

import java.util.Map;
import java.util.UUID;

/**
 * 一次工具调用的不可变来源与审计元数据。
 *
 * <p>{@link #arguments()} 只供执行与授权判断，默认审计实现不会记录其值，避免凭据进入日志。</p>
 */
public record ToolInvocation(
        String id,
        String toolName,
        String description,
        ToolCallOrigin origin,
        Source source,
        Map<String, ?> arguments) {

    public ToolInvocation {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        description = description == null ? "" : description;
        origin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
        source = source == null ? Source.agent("unknown") : source;
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static ToolInvocation plugin(String pluginId, String toolName, String description) {
        return new ToolInvocation(null, toolName, description,
                ToolCallOrigin.scheduled("plugin:" + pluginId),
                Source.plugin(pluginId), Map.of());
    }

    public record Source(Kind kind, String id) {
        public Source {
            kind = kind == null ? Kind.AGENT : kind;
            id = id == null ? "" : id;
        }

        public static Source plugin(String id) {
            return new Source(Kind.PLUGIN, id);
        }

        public static Source agent(String id) {
            return new Source(Kind.AGENT, id);
        }
    }

    public enum Kind {
        UI,
        AGENT,
        SHELL,
        SCHEDULE,
        PLUGIN,
        WORKFLOW
    }
}
