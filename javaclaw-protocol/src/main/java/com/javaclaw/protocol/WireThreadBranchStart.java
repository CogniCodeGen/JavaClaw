package com.javaclaw.protocol;

/**
 * 新分支及其首个 Turn 的组合结果；源 Thread 不包含在响应中且不会被修改。
 *
 * @param thread 新分支
 * @param turn 新分支首个 Turn
 */
public record WireThreadBranchStart(WireThread thread, WireTurn turn) {}
