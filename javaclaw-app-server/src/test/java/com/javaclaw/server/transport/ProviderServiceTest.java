package com.javaclaw.server.transport;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.server.model.ProviderService;
import com.javaclaw.server.model.ReloadableCloudModelGateway;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.persistence.H2ProfileRepository;
import com.javaclaw.server.persistence.H2ProviderConfigStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderServiceTest {
    @TempDir
    Path temporary;

    @Test
    void exposesOnlyMetadataAndHotReloadsVersionedCloudConfiguration() {
        try (H2Persistence store = new H2Persistence(temporary.resolve("data-v4"))) {
            var secrets = store.secretStore(temporary.resolve("config-v4"));
            try (ReloadableCloudModelGateway models = new ReloadableCloudModelGateway(secrets, Map.of())) {
                ProviderService providers =
                        new ProviderService(new H2ProviderConfigStore(store.database()), secrets, models);
                var configured = providers.configure(
                        "openai",
                        Map.of(
                                "model",
                                "gpt-5",
                                "embeddingModel",
                                "text-embedding-3-small",
                                "baseUrl",
                                "https://api.example.test"),
                        0,
                        "configure-openai");
                assertEquals(1, configured.configRevision());
                assertTrue(configured.configured());
                assertThrows(
                        IllegalArgumentException.class,
                        () -> providers.configure(
                                "openai",
                                Map.of("baseUrl", "https://secret@example.test"),
                                configured.configRevision(),
                                "bad-url"));

                char[] key = "temporary-test-key".toCharArray();
                var metadata = providers.setCredential("openai", key, "credential-openai");
                java.util.Arrays.fill(key, '\0');
                assertTrue(metadata.configured());
                var view = providers.list().stream()
                        .filter(value -> value.id().equals("openai"))
                        .findFirst()
                        .orElseThrow();
                assertTrue(view.configured());
                assertEquals(1, view.credentialRevision());
                assertTrue(providers.clearCredential("openai", metadata.revision(), "clear-openai"));
                assertTrue(providers.clearCredential("openai", metadata.revision(), "clear-openai"));
                assertTrue(providers.list().stream()
                        .filter(value -> value.id().equals("openai"))
                        .findFirst()
                        .orElseThrow()
                        .configured());
            }
        }
    }

    @Test
    void synchronizesPersistedAndNewProviderDefaultsIntoBuiltinProfiles() {
        try (H2Persistence store = new H2Persistence(temporary.resolve("sync-data-v4"))) {
            var secrets = store.secretStore(temporary.resolve("sync-config-v4"));
            var profiles = new ProfileService(new H2ProfileRepository(store.database()), Set.of());
            profiles.put(profile("profile_chat", "Chat", "gpt-5"), 0, "seed-chat");
            profiles.put(profile("custom-chat", "Custom", "gpt-5"), 0, "seed-custom");
            var configurations = new H2ProviderConfigStore(store.database());
            configurations.put(
                    "openai",
                    Map.of("model", "qwen3.8-max", "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                    0,
                    "seed-provider");

            try (ReloadableCloudModelGateway models = new ReloadableCloudModelGateway(secrets, Map.of())) {
                ProviderService providers = new ProviderService(configurations, secrets, models, profiles);

                assertEquals("qwen3.8-max", profiles.read("profile_chat").model());
                assertEquals("gpt-5", profiles.read("custom-chat").model());

                var manuallyChanged = profiles.read("profile_chat");
                profiles.put(
                        profile("profile_chat", "Chat", "gpt-5"), manuallyChanged.revision(), "manual-profile-change");
                new ProviderService(configurations, secrets, models, profiles);
                assertEquals("qwen3.8-max", profiles.read("profile_chat").model());

                providers.configure(
                        "openai",
                        Map.of(
                                "model",
                                "qwen3.8-flash",
                                "baseUrl",
                                "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                        1,
                        "update-provider");

                assertEquals("qwen3.8-flash", profiles.read("profile_chat").model());
                assertEquals("gpt-5", profiles.read("custom-chat").model());
            }
        }
    }

    private static ProfileRepository.ProfileDraft profile(String id, String name, String model) {
        return new ProfileRepository.ProfileDraft(
                id, name, ProfileKind.CHAT, "openai", model, "", Set.of(), SandboxMode.READ_ONLY, 16, 16, Map.of());
    }
}
