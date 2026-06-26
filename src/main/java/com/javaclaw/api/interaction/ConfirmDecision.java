package com.javaclaw.api.interaction;

/**
 * 用户对一次工具确认的决定 — 三态。
 *
 * <p>替代旧的 {@code boolean} 返回值，让调用方区分"放行"是单次行为还是
 * 永久授权（在当前任务范围内）：</p>
 *
 * <ul>
 *   <li>{@link #DENY}       — 拒绝本次执行（包括用户取消、超时、关键词不匹配等场景）</li>
 *   <li>{@link #ALLOW_ONCE} — 仅同意本次执行，下次同名工具调用仍需弹窗确认</li>
 *   <li>{@link #ALLOW_ALL}  — 同意，并把当前工具加入"任务级白名单"，
 *       本次任务内后续同名工具调用直接放行不再弹窗。任务结束自动清空。</li>
 * </ul>
 */
public enum ConfirmDecision {
    DENY,
    ALLOW_ONCE,
    ALLOW_ALL;

    /** 是否最终应放行执行 */
    public boolean isAllow() {
        return this != DENY;
    }
}
