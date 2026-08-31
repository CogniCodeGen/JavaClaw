package com.javaclaw.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.javaclaw.agent.runtime.TurnExecutionContext;

/** Resolves independent providers without allowing one failed provider to hide healthy tools. */
public final class CompositeToolProvider {
    private final List<ToolProvider> providers;

    /** 复制非空 Provider 列表；至少需要一个 Provider，运行中不改变发现顺序。 */
    public CompositeToolProvider(List<ToolProvider> providers) {
        this.providers = List.copyOf(providers);
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("at least one tool provider is required");
        }
    }

    /** 独立发现每个 Provider 的工具；单个失败转为 Failure，不隐藏其他可用工具，调用方负责关闭返回 scope。 */
    public Snapshot snapshot(TurnExecutionContext context) {
        Objects.requireNonNull(context, "context");
        ArrayList<Contribution> contributions = new ArrayList<>();
        ArrayList<Failure> failures = new ArrayList<>();
        ArrayList<AutoCloseable> scopes = new ArrayList<>();
        for (ToolProvider provider : providers) {
            try {
                String providerId = ToolProviderSupport.requireId(provider.id());
                ToolProvider.Snapshot resolved = provider.snapshot(context);
                contributions.add(new Contribution(providerId, resolved.tools()));
                resolved.failures()
                        .forEach(failure ->
                                failures.add(new Failure(providerId + "/" + failure.sourceId(), failure.message())));
                scopes.add(resolved.scope());
            } catch (Exception failure) {
                String message =
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                failures.add(new Failure(provider.id(), message));
            }
        }
        return new Snapshot(List.copyOf(contributions), List.copyOf(failures), List.copyOf(scopes));
    }

    /**
     * 本 Turn 的工具贡献、发现错误与资源租约；close 按逆序释放全部 scope 并聚合关闭异常。
     *
     * @param contributions 按 Provider 顺序排列的工具贡献，生产调用传入固定列表
     * @param failures 独立发现失败的诊断列表，允许为空
     * @param scopes 本快照持有的非空资源租约列表，由 close 释放
     */
    public record Snapshot(List<Contribution> contributions, List<Failure> failures, List<AutoCloseable> scopes)
            implements AutoCloseable {
        @Override
        public void close() {
            RuntimeException aggregate = null;
            for (int index = scopes.size() - 1; index >= 0; index--) {
                try {
                    scopes.get(index).close();
                } catch (Exception failure) {
                    if (aggregate == null) {
                        aggregate = new IllegalStateException("tool provider scope could not close");
                    }
                    aggregate.addSuppressed(failure);
                }
            }
            if (aggregate != null) {
                throw aggregate;
            }
        }
    }

    /**
     * 一个 Provider 成功发现的工具集合。
     *
     * @param providerId 贡献者标识
     * @param tools 该贡献者在本 Turn 可见的工具列表
     */
    public record Contribution(String providerId, List<RegisteredTool> tools) {}

    /**
     * 独立 Provider 发现失败；错误进入 Item 前必须脱敏。
     *
     * @param providerId 失败的 Provider 或其子来源标识
     * @param message 发现错误摘要，可能仍需脱敏
     */
    public record Failure(String providerId, String message) {}
}
