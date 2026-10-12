package com.javaclaw.ui.javafx.desktop;

import com.javaclaw.desktop.api.DesktopActionEvent;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopVirtualInputState;

/** Latest attempt only: terminal feedback is complete even when transient positions are coalesced. */
final class DesktopPreviewFeedback {
    private long actionId = -1;
    private long clearedPointerActionId = -1;
    private long generation;
    private long newestAtMillis;
    private boolean terminal;
    private DesktopActionResult.Delivery delivery;
    private long terminalAtNanos;
    private DesktopActionEvent action;
    private DesktopVirtualInputState input;

    synchronized boolean accept(DesktopActionEvent next, long nowNanos) {
        boolean finished = next.phase() == DesktopActionEvent.Phase.FINISHED;
        if (!admit(next.actionId(), next.atMillis(), finished)) return false;
        action = next;
        if (finished) {
            finish(nowNanos);
            delivery = next.delivery();
            if (next.delivery() == DesktopActionResult.Delivery.NOT_SENT) input = null;
            else if (input != null) input = finished(input, next.delivery());
        }
        return true;
    }

    synchronized boolean accept(DesktopVirtualInputState next, long nowNanos) {
        if (next.frameGeneration() < generation || next.actionId() <= clearedPointerActionId) return false;
        boolean finished = next.phase() == DesktopVirtualInputState.Phase.FINISHED;
        if (!admit(next.actionId(), next.atMillis(), finished)) return false;
        if (finished) finish(nowNanos);
        DesktopActionResult.Delivery delivery = action != null
                && action.phase() == DesktopActionEvent.Phase.FINISHED ? action.delivery() : next.delivery();
        if (finished) this.delivery = delivery;
        input = delivery == DesktopActionResult.Delivery.NOT_SENT ? null
                : finished ? finished(next, delivery) : next;
        return true;
    }

    private boolean admit(long id, long atMillis, boolean finished) {
        if (id < actionId) return false;
        // Legacy constructors have no attempt ID. Keep their timestamp ordering during migration.
        boolean legacyNew = id == 0 && actionId == 0 && !finished && terminal && atMillis > newestAtMillis;
        if (id > actionId || legacyNew) {
            actionId = id;
            terminal = false;
            delivery = null;
            action = null;
            input = null;
        } else if ((terminal && !finished) || (id == 0 && atMillis < newestAtMillis)) return false;
        newestAtMillis = Math.max(newestAtMillis, atMillis);
        return true;
    }

    private void finish(long nowNanos) {
        if (!terminal) terminalAtNanos = nowNanos;
        terminal = true;
    }

    private static DesktopVirtualInputState finished(DesktopVirtualInputState source,
            DesktopActionResult.Delivery delivery) {
        return new DesktopVirtualInputState(source.sessionId(), source.frameGeneration(),
                source.frameX(), source.frameY(), source.visible(), 0,
                DesktopVirtualInputState.Phase.FINISHED, source.atMillis(), source.actionId(), delivery);
    }

    synchronized DesktopVirtualInputState visibleInput(long nowNanos) {
        if (input == null || !input.visible() || input.frameGeneration() != generation) return null;
        if (terminal) {
            long durationNanos = input.delivery() == DesktopActionResult.Delivery.MAYBE_SENT
                    ? 2_000_000_000L : 1_000_000_000L;
            if (nowNanos - terminalAtNanos >= durationNanos) return null;
        }
        return input;
    }

    synchronized void generation(long current) {
        generation = current;
        if (input != null && input.frameGeneration() != current) input = null;
    }

    synchronized void clearPointer() {
        input = null;
        clearedPointerActionId = Math.max(clearedPointerActionId, actionId);
    }

    synchronized String text() {
        if (action != null && action.phase() == DesktopActionEvent.Phase.FINISHED) {
            if (action.delivery() == DesktopActionResult.Delivery.NOT_SENT)
                return "未派发 · " + reasonName(action.reason(), action.result());
            if (action.delivery() == DesktopActionResult.Delivery.MAYBE_SENT)
                return "结果未知 · 请观察，禁止自动重试";
            return "已派发，效果待观察";
        }
        if (terminal) return switch (delivery) {
            case NOT_SENT -> "未派发";
            case MAYBE_SENT -> "结果未知 · 请观察，禁止自动重试";
            case SENT -> "已派发，效果待观察";
        };
        return actionId >= 0 ? "准备操作" : null;
    }

    private static String reasonName(DesktopActionResult.Reason reason, DesktopActionResult.Status status) {
        return switch (reason) {
            case STALE_OBSERVATION -> "画面已过期";
            case ACCESS_DENIED -> "权限不可用";
            case INVALID_TARGET -> "目标不可用";
            case SESSION_CONTROL_REQUIRED -> "需要控制授权";
            case SYSTEM_INPUT_REQUIRED -> "需要前台输入模式";
            case NO_PROGRESS -> "已阻止重复点击";
            case POLICY_BLOCKED -> "输入已暂停";
            case TARGET_ACTIVE -> "目标正在被用户使用";
            case NO_SEMANTIC_PATH, UNSUPPORTED_ACTION -> "当前输入模式不支持";
            case PLATFORM_FAILURE -> "输入接口失败";
            case DELIVERY_UNCERTAIN -> "需要重新观察";
            case NONE -> switch (status) {
                case STALE_FRAME -> "画面已过期";
                case DENIED -> "未获授权";
                case UNSUPPORTED -> "当前输入模式不支持";
                default -> "操作未执行";
            };
        };
    }
}
