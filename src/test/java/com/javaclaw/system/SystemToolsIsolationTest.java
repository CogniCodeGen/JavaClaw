package com.javaclaw.system;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemToolsIsolationTest {

    private boolean previousConfirmationState;

    @BeforeEach
    void disableConfirmation() {
        previousConfirmationState = ToolConfirmationManager.isEnabled();
        ToolConfirmationManager.setEnabled(false);
    }

    @AfterEach
    void restoreConfirmation() {
        ToolConfirmationManager.setEnabled(previousConfirmationState);
    }

    @Test
    void rejectsOutsideAndManagedFilesButReadsOrdinaryProjectFile(@TempDir Path outside) {
        SystemTools tools = new SystemTools(ToolCallOrigin.INTERACTIVE);

        assertTrue(tools.fileRead(outside.resolve("secret.txt").toString()).contains("[失败]"));
        assertTrue(tools.fileRead("data/javaclaw.mv.db").contains("[失败]"));
        assertTrue(tools.fileRead("pom.xml").contains("[成功]"));
    }

    @Test
    void desktopCaptureAndInputAreDisabledBeforeExecution() {
        SystemTools tools = new SystemTools(ToolCallOrigin.INTERACTIVE);

        assertTrue(tools.screenshot().contains("严格项目文件隔离"));
        assertTrue(tools.keyType("不会输入").contains("严格项目文件隔离"));
    }

    @Test
    void refusesToPersistCredentialLikeContent() {
        SystemTools tools = new SystemTools(ToolCallOrigin.INTERACTIVE);

        String response = tools.fileWrite("target/should-not-exist-secret.txt",
                "password: RealSecret-2026!");

        assertTrue(response.contains("[失败]") && response.contains("疑似凭据"), response);
    }
}
