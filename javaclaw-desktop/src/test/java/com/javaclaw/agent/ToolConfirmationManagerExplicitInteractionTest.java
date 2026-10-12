package com.javaclaw.agent;

import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolConfirmationManagerExplicitInteractionTest {

    @Test
    void explicitInteractionStillWaitsWhenRiskConfirmationIsDisabled() {
        UserInteractionPort oldPort = ToolConfirmationManager.getPort();
        boolean oldEnabled = ToolConfirmationManager.isEnabled();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ConfirmRequest> seen = new AtomicReference<>();
        try {
            ToolConfirmationManager.setEnabled(false);
            ToolConfirmationManager.setPort(new UserInteractionPort() {
                @Override
                public boolean confirm(ConfirmRequest request) {
                    calls.incrementAndGet();
                    seen.set(request);
                    return true;
                }

                @Override
                public void notify(ToastRequest request) {
                }
            });

            boolean allowed = ToolConfirmationManager.requestExplicitUserConfirmation(
                    "保存站点", "是否保存登录会话？", 120);

            assertTrue(allowed);
            assertEquals(1, calls.get());
            assertEquals("保存站点", seen.get().toolName());
            assertEquals(120, seen.get().timeoutSeconds());
        } finally {
            ToolConfirmationManager.setPort(oldPort);
            ToolConfirmationManager.setEnabled(oldEnabled);
        }
    }
}
