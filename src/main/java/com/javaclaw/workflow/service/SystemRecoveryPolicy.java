package com.javaclaw.workflow.service;

/**
 * 系统图发现同 thread 的遗留运行时，恢复完成后的后续动作。
 *
 * <p>会话消息使用 {@link #RESUME_THEN_START}：先恢复上一条消息，再处理当前消息。
 * SDD 的 {@code resume()} 本身就是对遗留运行的恢复请求，使用 {@link #RESUME_ONLY}，
 * 避免同一个编排器在恢复完成后又被启动一次。</p>
 */
public enum SystemRecoveryPolicy {
    RESUME_THEN_START,
    RESUME_ONLY
}
