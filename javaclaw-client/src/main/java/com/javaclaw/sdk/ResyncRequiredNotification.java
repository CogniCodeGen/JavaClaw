package com.javaclaw.sdk;

/**
 * 通知客户端已出现投影缺口，应从持久游标恢复而不是等待遗漏的 delta。
 *
 * @param threadId 待恢复的 Thread；连接级重同步通知可为空字符串
 * @param afterSequence 排他的持久恢复游标；只读取其后的事件
 * @param reason 通知序列丢失或客户端过慢等需要恢复的原因
 */
public record ResyncRequiredNotification(String threadId, long afterSequence, String reason)
        implements ClientNotification {}
