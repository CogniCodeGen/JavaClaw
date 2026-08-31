package com.javaclaw.desktop;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.ProfileInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MainControllerPresentationTest {
    @Test
    void composerUsesChineseModeNamesWithoutExposingTheConfiguredModel() {
        var profile = profile("profile_chat", "Chat", "CHAT");

        String label = MainController.profileModeLabel(profile);

        assertEquals("对话", label);
        assertEquals("规划", MainController.profileModeLabel(profile("profile_plan", "Plan", "PLAN")));
        assertFalse(label.contains(profile.model()));
    }

    @Test
    void tokenUsageUsesRoundedWholeKilounitsAndKeepsUnknownDistinctFromZero() {
        assertEquals("26k", MainController.token(true, 26_452));
        assertEquals("9k", MainController.token(true, 9_334));
        assertEquals("8k", MainController.token(true, 8_123));
        assertEquals("1k", MainController.token(true, 1_499));
        assertEquals("2k", MainController.token(true, 1_500));
        assertEquals("0k", MainController.token(true, 424));
        assertEquals("0k", MainController.token(true, 0));
        assertEquals("—", MainController.token(false, 0));
    }

    private static ProfileInfo profile(String id, String name, String kind) {
        return new ProfileInfo(
                id,
                name,
                kind,
                "openai",
                "gpt-6",
                "system",
                Set.of("read"),
                "READ_ONLY",
                16,
                16,
                Map.of(),
                7,
                Instant.EPOCH);
    }
}
