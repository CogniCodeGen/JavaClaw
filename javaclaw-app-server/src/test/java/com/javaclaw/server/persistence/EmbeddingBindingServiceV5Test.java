package com.javaclaw.server.persistence;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingBindingServiceV5Test {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 精确Binding写入H2并在重启后恢复且最新停用立即阻断() {
        Path dataRoot = temporaryDirectory.resolve("data-v5");
        H2Database database = new H2Database(dataRoot);
        database.initialize();
        CanonicalJson json = new CanonicalJson();
        ProviderService providers = new ProviderService(database, credential -> true, json, CLOCK);
        ProviderEndpointSpec spec = modelSpec("Embedding", "embed-v1", ProviderModelPurpose.EMBEDDING);
        ProviderEndpoint provider = providers.create(
                identity(json, "provider/create", "provider-create", 0, spec),
                "embedding-provider",
                spec,
                ProviderLifecycle.ACTIVE);
        EmbeddingBindingService bindings = new EmbeddingBindingService(database, providers, json, CLOCK);
        ProviderRef reference = new ProviderRef(provider.id(), provider.revision(), "embed-v1");

        EmbeddingBinding first = bindings.update(
                identity(json, "provider/embeddingBinding/update", "binding-create", 0, reference), reference);

        H2Database restartedDatabase = new H2Database(dataRoot);
        restartedDatabase.initialize();
        ProviderService restartedProviders = new ProviderService(restartedDatabase, credential -> true, json, CLOCK);
        EmbeddingBindingService restarted =
                new EmbeddingBindingService(restartedDatabase, restartedProviders, json, CLOCK);
        assertEquals(Optional.of(first), restarted.find());

        providers.update(
                identity(json, "provider/update", "provider-disable", provider.revision(), spec),
                provider.id(),
                spec,
                ProviderLifecycle.DISABLED);
        assertThrows(
                PersistenceException.class,
                () -> bindings.update(
                        identity(json, "provider/embeddingBinding/update", "binding-disabled", 1, reference),
                        reference));
        assertEquals(Optional.of(first), bindings.find());
    }

    @Test
    void Binding拒绝Chat模型且Active壳必须具备模型和凭据引用() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        CanonicalJson json = new CanonicalJson();
        ProviderService providers = new ProviderService(database, credential -> true, json, CLOCK);
        ProviderEndpointSpec chat = modelSpec("Chat", "chat-v1", ProviderModelPurpose.CHAT);
        ProviderEndpoint provider = providers.create(
                identity(json, "provider/create", "chat-create", 0, chat),
                "chat-provider",
                chat,
                ProviderLifecycle.ACTIVE);
        EmbeddingBindingService bindings = new EmbeddingBindingService(database, providers, json, CLOCK);
        ProviderRef chatReference = new ProviderRef(provider.id(), provider.revision(), "chat-v1");

        assertThrows(
                PersistenceException.class,
                () -> bindings.update(
                        identity(json, "provider/embeddingBinding/update", "chat-binding", 0, chatReference),
                        chatReference));

        ProviderEndpointSpec disabledShell = new ProviderEndpointSpec(
                "Shell",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:11434/v1")),
                ProviderAuthentication.API_KEY,
                List.of(),
                Optional.empty(),
                Duration.ofSeconds(2),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        ProviderEndpoint shell = providers.create(
                identity(json, "provider/create", "shell-create", 0, disabledShell),
                "provider-shell",
                disabledShell,
                ProviderLifecycle.DISABLED);
        assertThrows(
                PersistenceException.class,
                () -> providers.update(
                        identity(json, "provider/update", "empty-enable", shell.revision(), disabledShell),
                        shell.id(),
                        disabledShell,
                        ProviderLifecycle.ACTIVE));

        ProviderEndpointSpec missingCredential = new ProviderEndpointSpec(
                disabledShell.displayName(),
                disabledShell.adapter(),
                disabledShell.baseUri(),
                disabledShell.authentication(),
                List.of(new ProviderModelSpec(
                        "chat-v1", "chat-v1", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                disabledShell.credential(),
                disabledShell.timeout(),
                disabledShell.maximumRetries(),
                disabledShell.options());
        assertThrows(
                PersistenceException.class,
                () -> providers.update(
                        identity(json, "provider/update", "credential-enable", shell.revision(), missingCredential),
                        shell.id(),
                        missingCredential,
                        ProviderLifecycle.ACTIVE));
    }

    private static ProviderEndpointSpec modelSpec(String displayName, String modelId, ProviderModelPurpose purpose) {
        return new ProviderEndpointSpec(
                displayName,
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:11434/v1")),
                ProviderAuthentication.NONE,
                List.of(new ProviderModelSpec(modelId, modelId, Set.of(purpose), OptionalInt.empty())),
                Optional.empty(),
                Duration.ofSeconds(2),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private static CommandIdentity identity(
            CanonicalJson json, String method, String key, long expectedRevision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, expectedRevision, json.encode(payload)), json);
    }
}
