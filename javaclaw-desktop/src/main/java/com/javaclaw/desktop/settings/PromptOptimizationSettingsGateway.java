package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.PromptOptimizationAdoption;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;

/** Prompt 优化面板访问 Java SDK 的异步边界。 */
public interface PromptOptimizationSettingsGateway {
    /** @return 当前 Workspace 目录 */
    CompletionStage<List<Workspace>> workspaces();

    /**
     * 启动可能计费的普通 Harness Turn。
     *
     * @param workspaceId Workspace
     * @param profile 精确源 Profile
     * @param billingConfirmed 显式确认标记
     * @param confirmation 固定计费确认文本
     * @param options expected revision 为 0
     * @return 初始任务投影
     */
    CompletionStage<PromptOptimizationDraft> start(
            WorkspaceId workspaceId,
            AgentProfileRef profile,
            boolean billingConfirmed,
            String confirmation,
            CommandOptions options);

    /**
     * 列出 Workspace 的优化任务。
     *
     * @param workspaceId Workspace
     * @return 任务目录
     */
    CompletionStage<List<PromptOptimizationDraft>> list(WorkspaceId workspaceId);

    /**
     * 读取任务最新状态。
     *
     * @param id 任务标识
     * @return 最新投影
     */
    CompletionStage<PromptOptimizationDraft> read(PromptOptimizationId id);

    /**
     * 取消活动 Turn。
     *
     * @param id 任务标识
     * @param reason 脱敏原因
     * @param options 当前 Turn revision
     * @return 最新投影
     */
    CompletionStage<PromptOptimizationDraft> cancel(PromptOptimizationId id, String reason, CommandOptions options);

    /**
     * 人工采纳 READY 草稿。
     *
     * @param id 任务标识
     * @param adoptionConfirmed 显式确认标记
     * @param confirmation 固定采纳确认文本
     * @param options 源 Profile revision
     * @return 新 Profile 与保留草稿
     */
    CompletionStage<PromptOptimizationAdoption> adopt(
            PromptOptimizationId id, boolean adoptionConfirmed, String confirmation, CommandOptions options);
}
