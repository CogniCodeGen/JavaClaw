package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ModelContextLimits;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.ProviderEndpointTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderContextServiceTest {
    @TempDir
    Path directory;

    @Test
    void 容量更新创建精确版本且重试不重复递增普通更新继承容量() {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        CanonicalJson json = new CanonicalJson();
        var providers = new ProviderService(database, ignored -> true, json, Clock.systemUTC());
        var service = new ProviderContextService(database, providers, json, Clock.systemUTC());
        var spec = ProviderEndpointTestFixtures.chat("容量测试", ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
        var created = providers.create(
                identity(json, "provider/create", "create", 0, spec), "context", spec, ProviderLifecycle.ACTIVE);
        var reference = new ProviderRef(created.id(), created.revision(), "test-model");
        assertTrue(service.read(reference).contextWindowTokens().isEmpty());
        var limits = new ModelContextLimits(reference, OptionalLong.of(128_000), OptionalLong.of(8_000));
        var identity = identity(json, "provider/modelContext/update", "capacity", 1, limits);

        var saved = service.update(identity, limits);

        assertEquals(2, saved.provider().endpointRevision());
        assertEquals(saved, service.update(identity, limits));
        assertTrue(service.read(reference).contextWindowTokens().isEmpty());
        assertEquals(spec, providers.require("context", 2).spec());
        providers.update(
                identity(json, "provider/update", "ordinary", 2, spec), "context", spec, ProviderLifecycle.DISABLED);
        assertEquals(
                OptionalLong.of(128_000),
                service.read(new ProviderRef("context", 3, "test-model")).contextWindowTokens());
        assertThrows(
                PersistenceException.class,
                () -> service.update(identity(json, "provider/modelContext/update", "stale", 1, limits), limits));
        assertEquals(3, providers.listLatest().getFirst().revision());
    }

    @Test
    void 不存在的模型与归档版本不能被当成未知容量更新() {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        CanonicalJson json = new CanonicalJson();
        var providers = new ProviderService(database, ignored -> true, json, Clock.systemUTC());
        var service = new ProviderContextService(database, providers, json, Clock.systemUTC());
        var spec = ProviderEndpointTestFixtures.chat("容量测试", ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
        providers.create(
                identity(json, "provider/create", "create", 0, spec), "context", spec, ProviderLifecycle.ACTIVE);
        assertThrows(PersistenceException.class, () -> service.read(new ProviderRef("context", 1, "missing")));
        providers.archive(
                identity(json, "provider/archive", "archive", 1, java.util.Map.of("id", "context")), "context");
        var archived = ModelContextLimits.unknown(new ProviderRef("context", 2, "test-model"));
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity(json, "provider/modelContext/update", "capacity", 2, archived), archived));
    }

    private static CommandIdentity identity(
            CanonicalJson json, String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }
}
