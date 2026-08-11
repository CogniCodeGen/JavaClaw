package com.javaclaw.application.onboarding;

import com.javaclaw.application.error.RejectedException;
import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProbeCommand;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProviderSetup;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProviderSetupCommand;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingUseCaseTest {

    @Test
    void exposesStableProvidersAndPersistsNormalizedCloudSetup() {
        FakeSettings settings = new FakeSettings();
        OnboardingUseCase useCase = new OnboardingUseCase(settings, uri -> 401);

        assertEquals("DashScope", useCase.providers().getFirst().id());
        assertThrows(UnsupportedOperationException.class, () -> useCase.providers().clear());

        ProviderSetup saved = useCase.save(new ProviderSetupCommand(
                "OpenAI", " https://example.com/v1 ", " model-a ", " secret "));

        assertEquals("OpenAI", saved.provider().id());
        assertEquals("https://example.com/v1", saved.baseUrl());
        assertEquals("model-a", saved.modelName());
        assertEquals(saved, settings.setup);
        assertEquals("secret", settings.apiKey);
    }

    @Test
    void validatesProviderUrlModelAndCloudKeyWhileNormalizingLocalKey() {
        FakeSettings settings = new FakeSettings();
        OnboardingUseCase useCase = new OnboardingUseCase(settings, uri -> 200);

        assertThrows(ValidationException.class, () -> useCase.save(
                new ProviderSetupCommand(null, "https://example.com", "model", "key")));
        assertThrows(ValidationException.class, () -> useCase.save(
                new ProviderSetupCommand("OpenAI", "file:///tmp/model", "model", "key")));
        assertThrows(ValidationException.class, () -> useCase.save(
                new ProviderSetupCommand("OpenAI", "https://example.com", "", "key")));
        assertThrows(ValidationException.class, () -> useCase.save(
                new ProviderSetupCommand("OpenAI", "https://example.com", "model", "")));

        useCase.save(new ProviderSetupCommand(
                "Ollama", "http://localhost:11434/v1", "qwen", "ignored"));
        assertEquals("not-needed", settings.apiKey);
    }

    @Test
    void derivesProviderSpecificProbeUrisAndMapsTransportFailure() {
        FakeSettings settings = new FakeSettings();
        CapturingProbe probe = new CapturingProbe();
        OnboardingUseCase useCase = new OnboardingUseCase(settings, probe);

        assertEquals(401, useCase.probe(
                new ProbeCommand("OpenAI", "https://example.com/v1/")).statusCode());
        assertEquals(URI.create("https://example.com/v1/models"), probe.uri);

        assertEquals(401, useCase.probe(
                new ProbeCommand("Ollama", "http://localhost:11434/v1")).statusCode());
        assertEquals(URI.create("http://localhost:11434/api/tags"), probe.uri);

        probe.failure = new IOException("offline");
        assertThrows(RejectedException.class, () -> useCase.probe(
                new ProbeCommand("OpenAI", "https://example.com")));
    }

    @Test
    void completionMakesTheWizardUnnecessaryAndRemainsIdempotent() {
        FakeSettings settings = new FakeSettings();
        OnboardingUseCase useCase = new OnboardingUseCase(settings, uri -> 200);

        assertTrue(useCase.required());
        useCase.complete();
        useCase.complete();

        assertFalse(useCase.required());
        assertEquals(1, settings.completions.get());
    }

    private static final class FakeSettings implements OnboardingSettingsPort {
        private final AtomicInteger completions = new AtomicInteger();
        private volatile boolean completed;
        private volatile ProviderSetup setup;
        private volatile String apiKey;

        @Override public boolean completed() { return completed; }

        @Override
        public void save(ProviderSetup value, String secret) {
            setup = value;
            apiKey = secret;
        }

        @Override
        public void markCompleted() {
            completions.incrementAndGet();
            completed = true;
        }
    }

    private static final class CapturingProbe implements ConnectionProbePort {
        private volatile URI uri;
        private volatile IOException failure;

        @Override
        public int status(URI value) throws IOException {
            uri = value;
            if (failure != null) throw failure;
            return 401;
        }
    }
}
