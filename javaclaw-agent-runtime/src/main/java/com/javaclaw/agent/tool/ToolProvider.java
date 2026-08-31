package com.javaclaw.agent.tool;

import java.util.List;
import java.util.Objects;

import com.javaclaw.agent.runtime.TurnExecutionContext;

/** Supplies tools for one immutable Turn snapshot. */
public interface ToolProvider {
    /** 返回稳定且符合命名规则的 Provider 标识，用于冲突检测和失败隔离。 */
    String id();

    /**
     * 发现本 Turn 可见工具；不得在每个模型步骤重建目录。
     *
     * @throws Exception Provider 发现失败；聚合层会隔离该 Provider
     */
    List<RegisteredTool> tools(TurnExecutionContext context) throws Exception;

    /** 返回无需 Turn、网络发现或资源分配的管理目录；不构成执行授权，动态外部 Provider 默认不公开未确认描述符。 */
    default List<com.javaclaw.core.api.ToolDescriptor> catalog() {
        return List.of();
    }

    /**
     * Providers with per-Turn resources or partially available backends override this method. The runtime closes the
     * returned scope when the Turn's tool session closes.
     */
    default Snapshot snapshot(TurnExecutionContext context) throws Exception {
        return new Snapshot(tools(context), List.of(), () -> {});
    }

    /**
     * 单 Provider 的不可变工具目录与本 Turn 资源作用域，关闭会话时释放。
     *
     * @param tools 非空工具列表，构造时复制
     * @param failures 部分来源失败列表；null 归一为空列表
     * @param scope 需随本 Turn 释放的资源；null 归一为无操作关闭句柄
     */
    record Snapshot(List<RegisteredTool> tools, List<Failure> failures, AutoCloseable scope) implements AutoCloseable {
        /** 固定工具和错误列表并归一资源句柄；后续目录变化只影响新快照。 */
        public Snapshot {
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
            failures = failures == null ? List.of() : List.copyOf(failures);
            scope = scope == null ? () -> {} : scope;
        }

        @Override
        public void close() throws Exception {
            scope.close();
        }
    }

    /**
     * 单个外部来源的发现失败，不阻止其他来源供给工具。
     *
     * @param sourceId 符合命名规则的来源标识
     * @param message 非空错误摘要，写入 Item 前需脱敏
     */
    record Failure(String sourceId, String message) {
        /** 校验来源标识和错误引用；不在此处分配运行时错误 Item。 */
        public Failure {
            sourceId = ToolProviderSupport.requireId(sourceId);
            message = Objects.requireNonNull(message, "message");
        }
    }
}
