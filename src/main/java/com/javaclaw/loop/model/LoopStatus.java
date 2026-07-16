package com.javaclaw.loop.model;

/**
 * 循环状态快照：作为 {@code ConversationEvent.Custom} 的负载发给 UI，
 * 驱动「迭代计数 / 已满足准则 / 用量 / 停止原因 / 等待倒计时」小面板。
 *
 * @param iteration        当前轮次
 * @param decision         本轮裁决（直接携带枚举而非 name() 字符串：生产者 LoopController
 *                         手里就是枚举值，字符串通道会迫使 UI 反解析并静默兜底，
 *                         枚举演进时编译器只能保护消费端一半）
 * @param reason           可读说明（完成依据 / 停止原因 / 继续剩余项 / 等待原因）
 * @param satisfied        已满足准则数
 * @param total            准则总数
 * @param tokensUsed       本循环累计用量
 * @param nextDelaySeconds 下一轮开始前的等待秒数（0=立即；仅 CONTINUE 时有意义，
 *                         UI 据此展示「Ns 后开下一轮」）
 */
public record LoopStatus(int iteration, Decision decision, String reason,
                         int satisfied, int total, long tokensUsed, long nextDelaySeconds) {
}
