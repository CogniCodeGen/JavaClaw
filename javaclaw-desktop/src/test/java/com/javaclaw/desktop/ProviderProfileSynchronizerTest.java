package com.javaclaw.desktop;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.ProviderInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderProfileSynchronizerTest {
    @Test
    void providerModelUpdatesBuiltInsAndProfilesFollowingTheOldDefaultOnly() {
        var provider = new ProviderInfo("openai", true, 1, 2, "gpt-5", "", "", Instant.EPOCH);
        var candidates = ProviderProfileSynchronizer.candidates(
                provider,
                "gpt-6",
                List.of(
                        profile("profile_chat", "openai", "legacy-custom-value"),
                        profile("custom-following", "openai", "gpt-5"),
                        profile("custom-override", "openai", "claude-special"),
                        profile("other-provider", "google", "gpt-5"),
                        profile("already-current", "openai", "gpt-6")));

        assertEquals(
                List.of("profile_chat", "custom-following"),
                candidates.stream().map(ProfileInfo::id).toList());
    }

    @Test
    void changingTheModelPreservesTheProfileContractAndRevision() {
        ProfileInfo original = profile("profile_chat", "openai", "gpt-5");

        ProfileInfo updated = ProviderProfileSynchronizer.withModel(original, "gpt-6");

        assertEquals("gpt-6", updated.model());
        assertEquals(original.id(), updated.id());
        assertEquals(original.provider(), updated.provider());
        assertEquals(original.attributes(), updated.attributes());
        assertEquals(original.revision(), updated.revision());
    }

    private static ProfileInfo profile(String id, String provider, String model) {
        return new ProfileInfo(
                id,
                id,
                "CHAT",
                provider,
                model,
                "system",
                Set.of("read"),
                "READ_ONLY",
                16,
                16,
                Map.of("maxTokens", "200000"),
                7,
                Instant.EPOCH);
    }
}
