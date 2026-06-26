package com.javaclaw.task.sdd.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * SDD 托管任务的精简持久化模型 —— 取代 v5 那个塞满状态机字段的 ManagedTask。
 *
 * <p>只保留任务<b>身份与运行账目</b>；变更的实质内容（提案/规格/计划/进度）全在
 * {@code {workDir}/.agent/openspec/changes/{slug}/}，由 {@code SpecStore} 读出，不在此重复。
 * 进度（{@link #progress}）是 tasks.md 勾选折叠的缓存值，便于列表展示而不必每次读盘。</p>
 *
 * @author JavaClaw
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SddManagedTask {

    public String id;
    public String title;
    public String description;
    public String workDir;
    public String capabilities;
    public long tokenBudget;
    public String notificationChannel;
    public String createdAt;
    public String updatedAt;

    public SddTaskState state = SddTaskState.PENDING;
    /** tasks.md 勾选折叠的进度缓存（0–100）。 */
    public int progress;
    /** 终态结果说明（完成/失败/待人工原因）。 */
    public String result;

    public long totalInputTokens;
    public long totalOutputTokens;

    /** 按阶段分桶的 token 用量（phase → input / output），用于定位"钱花在哪"并验证优化。 */
    public java.util.Map<String, Long> phaseInputTokens = new java.util.LinkedHashMap<>();
    public java.util.Map<String, Long> phaseOutputTokens = new java.util.LinkedHashMap<>();

    public SddManagedTask() {}

    public SddManagedTask(String id, String title, String description, String workDir,
                          String capabilities, long tokenBudget, String notificationChannel,
                          String createdAt) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.workDir = workDir;
        this.capabilities = capabilities;
        this.tokenBudget = tokenBudget;
        this.notificationChannel = notificationChannel;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }
}
