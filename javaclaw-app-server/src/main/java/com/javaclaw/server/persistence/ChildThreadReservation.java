package com.javaclaw.server.persistence;

import java.util.Objects;

import com.javaclaw.api.ResolvedTurnConfig;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnId;

/**
 * 服务端解析后的子 Thread 创建意图，尚未调用子模型。
 *
 * @param parentTurnId 父 Turn 身份
 * @param configuration 子任务完整配置和已保存的 Prompt、目录摘要
 * @param toolCatalog 与配置摘要对应的完整冻结目录
 * @param executionIntent 只读或独立 Worktree，禁止直接写父 Workspace
 * @param title 子任务标题
 */
public record ChildThreadReservation(
        TurnId parentTurnId,
        ResolvedTurnConfig configuration,
        com.javaclaw.api.ToolCatalogSnapshot toolCatalog,
        ThreadExecutionIntent executionIntent,
        String title) {
    /** 验证所有字段及子任务文件隔离意图。 */
    public ChildThreadReservation {
        Objects.requireNonNull(parentTurnId, "parentTurnId");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(toolCatalog, "toolCatalog");
        if (!configuration.toolCatalogDigest().equals(toolCatalog.digest())) {
            throw new IllegalArgumentException("子任务配置与目录摘要不一致");
        }
        if (executionIntent != ThreadExecutionIntent.READ_ONLY
                && executionIntent != ThreadExecutionIntent.ISOLATED_WRITE) {
            throw new IllegalArgumentException("子任务必须只读或使用受管 Worktree");
        }
    }
}
