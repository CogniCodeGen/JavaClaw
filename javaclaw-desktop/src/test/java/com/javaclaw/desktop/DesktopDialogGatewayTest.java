package com.javaclaw.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopDialogGatewayTest {
    @Test
    void destructiveConfirmLabelsMapToDangerSemantics() {
        assertTrue(JavaFxDesktopDialogGateway.dangerous("删除对话"));
        assertTrue(JavaFxDesktopDialogGateway.dangerous("清除凭据"));
        assertTrue(JavaFxDesktopDialogGateway.dangerous("确认清理工作树"));
        assertTrue(JavaFxDesktopDialogGateway.dangerous("覆盖文件"));
        assertTrue(JavaFxDesktopDialogGateway.dangerous("卸载插件"));
        assertFalse(JavaFxDesktopDialogGateway.dangerous("保存配置"));
        assertFalse(JavaFxDesktopDialogGateway.dangerous("允许此次操作"));
    }
}
