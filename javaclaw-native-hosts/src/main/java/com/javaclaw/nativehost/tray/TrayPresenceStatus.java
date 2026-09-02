package com.javaclaw.nativehost.tray;

import java.util.Objects;
import java.util.Optional;

/**
 * 托盘 supervisor 的脱敏活动投影。
 *
 * @param active 心跳新鲜且进程仍存活
 * @param processId 活动托盘进程 PID；不活动时为空
 * @param unavailableReason 不活动或损坏时的原因
 */
public record TrayPresenceStatus(boolean active, Optional<Long> processId, Optional<String> unavailableReason) {
    /** 校验状态组合。 */
    public TrayPresenceStatus {
        processId = Objects.requireNonNull(processId, "processId");
        unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason");
        if (active != processId.isPresent() || active == unavailableReason.isPresent()) {
            throw new IllegalArgumentException("tray presence fields disagree");
        }
    }

    /** @param reason 脱敏原因 @return 不活动状态 */
    public static TrayPresenceStatus unavailable(String reason) {
        String detail = Objects.requireNonNull(reason, "reason").strip();
        if (detail.isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return new TrayPresenceStatus(false, Optional.empty(), Optional.of(detail));
    }
}
