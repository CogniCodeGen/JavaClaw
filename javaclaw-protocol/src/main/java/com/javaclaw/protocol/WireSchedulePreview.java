package com.javaclaw.protocol;

import java.time.Instant;
import java.util.List;

/**
 * Cron 校验和未来触发时间预览；不代表已保存或已启用任何 Schedule。
 *
 * @param cronExpression 已校验表达式
 * @param zoneId 已校验时区
 * @param fireTimes 未来触发时间
 */
public record WireSchedulePreview(String cronExpression, String zoneId, List<Instant> fireTimes) {
    /** 固定未来触发时间集合。 */
    public WireSchedulePreview {
        fireTimes = fireTimes == null ? List.of() : List.copyOf(fireTimes);
    }
}
