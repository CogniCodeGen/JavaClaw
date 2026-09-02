package com.javaclaw.launcher.tray;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.nativehost.tray.SystemTrayFeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwtSystemTrayViewTest {
    @Test
    void 平台探测不可用时在加载Awt控件前失败关闭() {
        SystemTrayFeature.Status unavailable =
                new SystemTrayFeature.Status(SystemTrayFeature.Platform.MACOS, false, Optional.of("当前会话没有图形桌面"));

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> new AwtSystemTrayView(unavailable));

        assertEquals("当前会话没有图形桌面", failure.getMessage());
        assertThrows(NullPointerException.class, () -> new AwtSystemTrayView(null));
    }

    @Test
    void 服务状态映射为稳定中文标签和设计令牌颜色() {
        TrayState unknown = state(TrayState.ServerState.UNKNOWN, false, Optional.empty());
        TrayState running = state(TrayState.ServerState.RUNNING, false, Optional.empty());
        TrayState stopped = state(TrayState.ServerState.STOPPED, false, Optional.empty());
        TrayState pending = state(TrayState.ServerState.RUNNING, true, Optional.empty());
        TrayState failed = state(TrayState.ServerState.RUNNING, false, Optional.of("失败"));

        assertEquals("未知", AwtSystemTrayView.serverLabel(unknown));
        assertEquals("运行中", AwtSystemTrayView.serverLabel(running));
        assertEquals("已停止", AwtSystemTrayView.serverLabel(stopped));
        assertEquals("处理中", AwtSystemTrayView.serverLabel(pending));
        assertEquals(new Color(0x706B5F), AwtSystemTrayView.color(unknown));
        assertEquals(new Color(0x10B981), AwtSystemTrayView.color(running));
        assertEquals(new Color(0x706B5F), AwtSystemTrayView.color(stopped));
        assertEquals(new Color(0xF59E0B), AwtSystemTrayView.color(pending));
        assertEquals(new Color(0xEF4444), AwtSystemTrayView.color(failed));
    }

    @Test
    void 托盘图像使用固定尺寸透明背景和可见中心点() {
        BufferedImage image = (BufferedImage) AwtSystemTrayView.image(new Color(0x2E9A6A));

        assertEquals(18, image.getWidth());
        assertEquals(18, image.getHeight());
        assertEquals(0, image.getRGB(0, 0));
        assertEquals(Color.WHITE.getRGB(), image.getRGB(9, 9));
        assertTrue((image.getRGB(2, 2) >>> 24) > 0);
    }

    private static TrayState state(TrayState.ServerState server, boolean pending, Optional<String> error) {
        return new TrayState(server, false, pending, "状态", error);
    }
}
