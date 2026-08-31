package com.javaclaw.desktop;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.ProfileInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SchedulePaneTest {
    @Test
    void cronZoneAndRequiredInputsFailBeforeAnySdkRequest() {
        ProfileInfo profile = profile();
        assertEquals("Quartz Cron 应包含 6 或 7 个字段", SchedulePane.validate("晨间任务", "执行", "* *", "Asia/Shanghai", profile));
        assertEquals(
                "请输入有效 IANA 时区，例如 Asia/Shanghai",
                SchedulePane.validate("晨间任务", "执行", "0 0 9 * * ?", "Mars/Base", profile));
        assertEquals("名称不能为空", SchedulePane.validate(" ", "执行", "0 0 9 * * ?", "Asia/Shanghai", profile));
        assertEquals("任务内容不能为空", SchedulePane.validate("晨间任务", " ", "0 0 9 * * ?", "Asia/Shanghai", profile));
        assertEquals("请选择执行 Profile", SchedulePane.validate("晨间任务", "执行", "0 0 9 * * ?", "Asia/Shanghai", null));
        assertNull(SchedulePane.validate("晨间任务", "执行", "0 0 9 * * ?", "Asia/Shanghai", profile));
    }

    private static ProfileInfo profile() {
        return new ProfileInfo(
                "schedule",
                "定时任务",
                "SCHEDULE",
                "openai",
                "fixture",
                "",
                Set.of(),
                "READ_ONLY",
                1,
                1,
                Map.of(),
                1,
                null);
    }
}
