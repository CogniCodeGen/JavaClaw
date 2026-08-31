package com.javaclaw.sdk.model;

import java.time.Instant;
import java.util.List;

/**
 * 未保存 Cron 表达式的校验与未来触发预览。
 *
 * @param cronExpression 已校验的 Quartz Cron 表达式
 * @param zoneId 已校验的 IANA 时区
 * @param fireTimes 未来触发时间；数量由请求 count 决定并在构造时复制
 */
public record SchedulePreviewInfo(String cronExpression, String zoneId, List<Instant> fireTimes) {
    /** 固定未来触发时间集合。 */
    public SchedulePreviewInfo {
        fireTimes = fireTimes == null ? List.of() : List.copyOf(fireTimes);
    }
}
