package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpHealth;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;

/**
 * MCP 管理页完整不可变状态；JavaFX 控件只负责渲染该快照。
 *
 * @param phase 页面加载阶段
 * @param workspaces 可选 Workspace 目录
 * @param workspaceId 当前 Workspace
 * @param grants 当前 Workspace 的 MCP 私网授权
 * @param endpoints 当前 Workspace 的 Endpoint
 * @param selection 当前编辑和只读投影
 * @param feedback 页面消息、脏状态和请求 epoch
 */
public record McpSettingsState(
        SettingsLoadState phase,
        List<Workspace> workspaces,
        Optional<WorkspaceId> workspaceId,
        List<PrivateNetworkGrant> grants,
        List<McpEndpoint> endpoints,
        Selection selection,
        Feedback feedback) {
    /** 复制集合并校验状态组成。 */
    public McpSettingsState {
        Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(workspaces);
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        grants = List.copyOf(grants);
        endpoints = List.copyOf(endpoints);
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(feedback, "feedback");
    }

    /** @return 初始空状态 */
    public static McpSettingsState initial() {
        McpEndpointDraft empty = McpEndpointDraft.empty(Optional.empty());
        return new McpSettingsState(
                SettingsLoadState.LOADING,
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(),
                Selection.empty(empty),
                new Feedback("", false, 0));
    }

    /** @return 表单是否有未保存变更 */
    public boolean dirty() {
        return feedback.dirty();
    }

    /**
     * 当前 Endpoint、草稿和只读投影。
     *
     * @param endpoint 已保存 Endpoint；新建草稿时为空
     * @param baseline 最近一次权威草稿
     * @param draft 当前表单草稿
     * @param health 最近一次脱敏健康检查
     * @param history Endpoint 不可变历史
     * @param catalog 已读取的 Catalog 页
     */
    public record Selection(
            Optional<McpEndpoint> endpoint,
            McpEndpointDraft baseline,
            McpEndpointDraft draft,
            Optional<McpHealth> health,
            List<McpEndpoint> history,
            Catalog catalog) {
        /** 复制目录与校验可空投影。 */
        public Selection {
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(draft, "draft");
            health = Objects.requireNonNull(health, "health");
            history = List.copyOf(history);
            Objects.requireNonNull(catalog, "catalog");
        }

        static Selection empty(McpEndpointDraft draft) {
            return new Selection(Optional.empty(), draft, draft, Optional.empty(), List.of(), Catalog.empty());
        }
    }

    /**
     * Catalog 分页投影。
     *
     * @param entries 已累计读取的条目
     * @param nextCursor 下一页 opaque cursor
     */
    public record Catalog(List<McpCatalogEntry> entries, Optional<String> nextCursor) {
        /** 复制条目并校验 cursor。 */
        public Catalog {
            entries = List.copyOf(entries);
            nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        }

        private static Catalog empty() {
            return new Catalog(List.of(), Optional.empty());
        }
    }

    /**
     * 页面消息、脏状态与请求 epoch。
     *
     * @param message 用户可读状态
     * @param dirty 是否存在未保存草稿
     * @param epoch 最新请求序号
     */
    public record Feedback(String message, boolean dirty, long epoch) {
        /** 规范化消息并校验 epoch。 */
        public Feedback {
            message = Objects.requireNonNullElse(message, "");
            if (epoch < 0) {
                throw new IllegalArgumentException("epoch must not be negative");
            }
        }
    }
}
