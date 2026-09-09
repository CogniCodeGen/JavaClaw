package com.javaclaw.desktop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherSessionTest {
    @AfterEach
    void 清理发行属性() {
        System.clearProperty(LauncherSession.SUPERVISED_PROPERTY);
        System.clearProperty(LauncherSession.TRAY_ACTIVE_PROPERTY);
    }

    @Test
    void Idea直跑展示当前一键启动入口() {
        LauncherSession session = LauncherSession.current();

        assertFalse(session.supervised());
        assertFalse(session.trayActive());
        assertEquals(
                "IDEA 直接运行没有 launcher supervisor；请运行“JavaClaw 一键启动（前后端）”或先启动 App Server。",
                session.recoveryInstruction());
    }

    @Test
    void 托盘发行会话展示真实恢复入口() {
        System.setProperty(LauncherSession.SUPERVISED_PROPERTY, "true");
        System.setProperty(LauncherSession.TRAY_ACTIVE_PROPERTY, "true");

        LauncherSession session = LauncherSession.current();

        assertTrue(session.supervised());
        assertTrue(session.trayActive());
        assertEquals("由系统托盘控制", session.controlLabel());
        assertTrue(session.recoveryInstruction().contains("系统托盘"));
    }

    @Test
    void 托盘不能脱离Supervisor被构造() {
        assertThrows(IllegalArgumentException.class, () -> new LauncherSession(false, true));
    }
}
