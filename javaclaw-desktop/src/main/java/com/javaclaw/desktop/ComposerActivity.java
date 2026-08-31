package com.javaclaw.desktop;

import java.util.Objects;

/** 输入区与当前 Turn 的交互状态；它独立于页面刷新、主题加载等通用后台操作。 */
record ComposerActivity(Phase phase, String turnId) {
    ComposerActivity {
        Objects.requireNonNull(phase, "phase");
        turnId = Objects.toString(turnId, "");
        if (phase == Phase.IDLE || phase == Phase.SUBMITTING || phase == Phase.CANCELLING_SUBMISSION) {
            turnId = "";
        } else if (turnId.isBlank()) {
            throw new IllegalArgumentException("活动 Turn 状态必须包含 turnId");
        }
    }

    static ComposerActivity idle() {
        return new ComposerActivity(Phase.IDLE, "");
    }

    static ComposerActivity submitting() {
        return new ComposerActivity(Phase.SUBMITTING, "");
    }

    static ComposerActivity cancellingSubmission() {
        return new ComposerActivity(Phase.CANCELLING_SUBMISSION, "");
    }

    boolean hasActiveTurn() {
        return switch (phase) {
            case ACTIVE, STEERING, WAITING_INTERACTION, INTERRUPTING -> true;
            case IDLE, SUBMITTING, CANCELLING_SUBMISSION -> false;
        };
    }

    boolean acceptsSteering() {
        return phase == Phase.ACTIVE;
    }

    boolean allowsAttachments() {
        return phase == Phase.IDLE;
    }

    boolean canInterrupt() {
        return phase == Phase.ACTIVE || phase == Phase.STEERING || phase == Phase.WAITING_INTERACTION;
    }

    /** 输入区有限状态；等待交互时只能通过对应持久 Item 回答。 */
    enum Phase {
        IDLE,
        SUBMITTING,
        CANCELLING_SUBMISSION,
        ACTIVE,
        STEERING,
        WAITING_INTERACTION,
        INTERRUPTING
    }
}
