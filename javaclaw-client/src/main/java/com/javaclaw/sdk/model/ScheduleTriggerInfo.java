package com.javaclaw.sdk.model;

/**
 * 一次手动 Schedule 触发的结果，跳过时不会创建新 Turn。
 *
 * @param skipped 是否因重叠或其他调度规则跳过
 * @param reason 需要用户判断的原因或跳过说明
 * @param turn 新启动 Turn；跳过时可为 null
 */
public record ScheduleTriggerInfo(boolean skipped, String reason, TurnInfo turn) {}
