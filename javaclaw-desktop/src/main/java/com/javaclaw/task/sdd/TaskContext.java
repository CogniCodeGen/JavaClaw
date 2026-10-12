package com.javaclaw.task.sdd;

/**
 * 编排器运行一个任务所需的最小上下文 —— 刻意与遗留 {@code ManagedTask} 解耦。
 *
 * <p>B5 接缝层负责从 {@code ManagedTask} 构造本记录；编排器与验证/真相层都只依赖此记录，
 * 不感知任务的持久化索引、token 统计等基础设施字段。</p>
 *
 * @param id           任务稳定标识
 * @param title        任务标题
 * @param description  用户原始需求描述
 * @param workDir      工作目录绝对路径（spec 产物与执行都落在此）
 * @param capabilities 能力声明（如 "system,command"；交由执行智能体组合工具）
 * @author JavaClaw
 */
public record TaskContext(String id, String title, String description, String workDir, String capabilities) {

    /** change 目录 slug（由 id+title 派生）。 */
    public String slug() {
        return com.javaclaw.task.sdd.spec.SpecPaths.makeSlug(id, title);
    }
}
