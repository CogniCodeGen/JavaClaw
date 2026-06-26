package com.javaclaw.task.sdd.run;

/**
 * SDD 任务事件监听 —— UI/前端订阅，按需增量刷新。全 default 空实现。
 *
 * @author JavaClaw
 */
public interface SddTaskListener {

    /** 任务状态/进度/token 等发生变更（task 为当前快照）。 */
    default void onTaskChanged(SddManagedTask task) {}

    /** 任务追加一行日志。 */
    default void onLog(String taskId, String taskTitle, String message) {}
}
