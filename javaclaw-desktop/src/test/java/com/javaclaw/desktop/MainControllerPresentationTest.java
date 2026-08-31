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
    void composerShowsTheModeNameWithoutExposingTheConfiguredModel() {
        var profile = new ProfileInfo(
                "profile_chat",
                "Chat",
                "CHAT",
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

        String label = MainController.profileModeLabel(profile);

        assertEquals("Chat", label);
        assertFalse(label.contains(profile.model()));
    }
}
