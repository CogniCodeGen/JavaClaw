package com.javaclaw.launcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelRequest;
import com.javaclaw.core.api.ModelStreamSink;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.server.model.SpringAiCloudModelGateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudStreamingContractTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"openai", "anthropic", "google"})
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void realAdaptersUseProviderProtocolsWithoutCallingToolsOrExternalModels(String provider) throws Exception {
        String prefix = provider.toUpperCase(java.util.Locale.ROOT);
        String baseUrlKey = provider.equals("google") ? "GOOGLE_GENAI_BASE_URL" : prefix + "_BASE_URL";
        try (var fixture = new DesktopModelFixture(provider);
                var gateway = SpringAiCloudModelGateway.fromEnvironment(
                        Map.of(prefix + "_API_KEY", "synthetic-test-key", baseUrlKey, fixture.baseUrl()))) {
            var response = gateway.stream(
                    new ModelRequest(
                            new ThreadId("fixture-thread"),
                            new TurnId("fixture-turn"),
                            List.of(new ModelMessage(ModelMessage.Role.USER, "合成视觉验收", null)),
                            List.of(),
                            new TurnConfig(
                                    "desktop-fixture",
                                    provider,
                                    "none",
                                    temporary,
                                    SandboxPolicy.readOnly(Set.of(temporary), Set.of()),
                                    ApprovalPolicy.NEVER,
                                    Set.of(),
                                    Map.of())),
                    ModelStreamSink.IGNORE);
            fixture.verify();
            assertEquals(DesktopModelFixture.ANSWER, response.text());
        }
    }
}
