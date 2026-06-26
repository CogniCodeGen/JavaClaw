package com.javaclaw.plugin.api.capability;

/**
 * SCHEDULE 能力 —— 插件经此向宿主注册定时任务（到点由宿主隔离编排器执行给定 prompt）。
 *
 * <p>需在 {@code plugin.json} 声明 {@link com.javaclaw.plugin.api.Capability#SCHEDULE}。
 * 插件创建的任务打有插件标签，<b>随插件停用一并清理</b>（与插件生命周期绑定，避免残留）。</p>
 *
 * @author JavaClaw
 */
public interface ScheduleAccess {

    /**
     * 创建固定间隔任务。
     *
     * @param name    任务名（中文友好）
     * @param minutes 间隔分钟数（≥1）
     * @param prompt  到点交给编排器执行的指令
     * @return 任务 id（可用于 {@link #cancel(String)}）
     */
    String createInterval(String name, int minutes, String prompt);

    /**
     * 创建每日定时任务。
     *
     * @param name   任务名
     * @param hhmm   每日触发时间，格式 {@code HH:mm}
     * @param prompt 执行指令
     * @return 任务 id
     */
    String createDaily(String name, String hhmm, String prompt);

    /**
     * 创建 Cron 任务。
     *
     * @param name   任务名
     * @param cron   Cron 表达式
     * @param prompt 执行指令
     * @return 任务 id
     */
    String createCron(String name, String cron, String prompt);

    /**
     * 取消插件此前创建的某个任务。
     *
     * @param taskId 任务 id
     */
    void cancel(String taskId);
}
