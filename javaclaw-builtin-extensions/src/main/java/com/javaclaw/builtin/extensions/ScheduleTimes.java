package com.javaclaw.builtin.extensions;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.quartz.CronExpression;

import com.javaclaw.builtin.contracts.ScheduleContracts;

/** 计算 Schedule 严格晚于基准时间的五个触发点。 */
final class ScheduleTimes {
    private ScheduleTimes() {}

    static ScheduleContracts.Preview preview(ScheduleContracts.Timing timing, Instant after) {
        return new ScheduleContracts.Preview(
                switch (timing.kind()) {
                    case CRON -> cron(timing, after);
                    case FIXED_INTERVAL -> fixed(timing, after);
                });
    }

    static void validate(ScheduleContracts.Timing timing) {
        preview(timing, Instant.EPOCH);
    }

    private static List<Instant> cron(ScheduleContracts.Timing timing, Instant after) {
        try {
            CronExpression expression =
                    new CronExpression(timing.cronExpression().orElseThrow());
            expression.setTimeZone(TimeZone.getTimeZone(timing.zoneId()));
            List<Instant> values = new ArrayList<>(5);
            Date cursor = Date.from(after);
            for (int index = 0; index < 5; index++) {
                Date next = expression.getNextValidTimeAfter(cursor);
                if (next == null) {
                    throw new IllegalArgumentException("Cron does not have five future occurrences");
                }
                values.add(next.toInstant());
                cursor = next;
            }
            return List.copyOf(values);
        } catch (ParseException failure) {
            throw new IllegalArgumentException("invalid Quartz Cron expression", failure);
        }
    }

    private static List<Instant> fixed(ScheduleContracts.Timing timing, Instant after) {
        Duration interval = timing.interval().orElseThrow();
        Instant first = timing.firstFireAt().orElseThrow();
        Instant next = first.isAfter(after) ? first : nextAfter(first, interval, after);
        List<Instant> values = new ArrayList<>(5);
        for (int index = 0; index < 5; index++) {
            values.add(next);
            next = next.plus(interval);
        }
        return List.copyOf(values);
    }

    private static Instant nextAfter(Instant first, Duration interval, Instant after) {
        long elapsedIntervals = Duration.between(first, after).dividedBy(interval);
        return first.plus(interval.multipliedBy(Math.addExact(elapsedIntervals, 1)));
    }
}
