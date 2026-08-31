package com.javaclaw.agent.automation;

import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;

import org.quartz.CronExpression;

/** 未保存 Cron 的无状态校验与未来触发计算；不读取或写入 Schedule Repository。 */
final class SchedulePreviews {
    private SchedulePreviews() {}

    static List<Instant> calculate(String cronExpression, String zoneId, int count, Instant after) {
        if (count < 1 || count > 20) {
            throw new IllegalArgumentException("count must be between 1 and 20");
        }
        String cron = Objects.requireNonNull(cronExpression, "cronExpression").strip();
        String zoneName = Objects.requireNonNull(zoneId, "zoneId").strip();
        if (cron.isEmpty() || zoneName.isEmpty()) {
            throw new IllegalArgumentException("cronExpression and zoneId are required");
        }
        ZoneId zone = ZoneId.of(zoneName);
        try {
            CronExpression expression = new CronExpression(cron);
            expression.setTimeZone(TimeZone.getTimeZone(zone));
            var result = new ArrayList<Instant>(count);
            Date cursor = Date.from(Objects.requireNonNull(after, "after"));
            for (int index = 0; index < count; index++) {
                Date next = expression.getNextValidTimeAfter(cursor);
                if (next == null) {
                    throw new IllegalArgumentException("cronExpression has no future fire time");
                }
                result.add(next.toInstant());
                cursor = next;
            }
            return List.copyOf(result);
        } catch (ParseException failure) {
            throw new IllegalArgumentException("cronExpression is invalid", failure);
        }
    }
}
