package com.javaclaw.sdk.model;

/**
 * 原子重试请求创建的分支和首个 Turn。
 *
 * @param thread 新分支 Thread
 * @param turn 已启动的首个 Turn
 */
public record ThreadBranchStartInfo(ThreadInfo thread, TurnInfo turn) {}
