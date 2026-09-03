package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.ProviderEndpointTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderServiceAtomicMutationTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 多注册表预构造失败时配置不落库且候选资源会释放() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        CanonicalJson json = new CanonicalJson();
        ProviderService service =
                new ProviderService(database, reference -> true, json, Clock.fixed(NOW, ZoneOffset.UTC));
        RecordingParticipant models = new RecordingParticipant();
        RecordingParticipant embeddings = new RecordingParticipant();
        service.participate(models);
        service.participate(embeddings);

        ProviderEndpointSpec firstSpec = spec("第一版");
        CommandIdentity create = identity(json, "provider/create", "provider-create", 0, firstSpec);
        ProviderEndpoint first = service.create(create, "primary", firstSpec, ProviderLifecycle.ACTIVE);
        assertEquals(first, service.create(create, "primary", firstSpec, ProviderLifecycle.ACTIVE));
        assertEquals(1, models.prepared.get());
        assertEquals(1, models.activated.get());
        assertEquals(1, embeddings.prepared.get());

        embeddings.fail = true;
        ProviderEndpointSpec rejectedSpec = spec("无效候选");
        CommandIdentity update = identity(json, "provider/update", "provider-update", 1, rejectedSpec);
        assertThrows(
                IllegalStateException.class,
                () -> service.update(update, "primary", rejectedSpec, ProviderLifecycle.ACTIVE));

        assertEquals(List.of(first), service.listLatest());
        assertEquals(2, models.prepared.get());
        assertEquals(1, models.activated.get());
        assertEquals(1, models.discarded.get());
        assertEquals(2, embeddings.prepared.get());
        assertEquals(1, embeddings.activated.get());
    }

    private static ProviderEndpointSpec spec(String name) {
        return ProviderEndpointTestFixtures.chat(name, ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
    }

    private static CommandIdentity identity(
            CanonicalJson json, String method, String key, long expectedRevision, Object payload) {
        WriteCommand command = new WriteCommand(key, expectedRevision, json.encode(payload));
        return CommandIdentity.from(method, command, json);
    }

    private static final class RecordingParticipant implements ProviderService.ProviderMutationParticipant {
        private final AtomicInteger prepared = new AtomicInteger();
        private final AtomicInteger activated = new AtomicInteger();
        private final AtomicInteger discarded = new AtomicInteger();
        private boolean fail;

        @Override
        public ProviderService.PreparedProviderChange prepare(ProviderEndpoint candidate) {
            prepared.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("candidate rejected");
            }
            return new ProviderService.PreparedProviderChange() {
                private boolean pending = true;

                @Override
                public void activate() {
                    if (!pending) {
                        throw new IllegalStateException("candidate already resolved");
                    }
                    pending = false;
                    activated.incrementAndGet();
                }

                @Override
                public void close() {
                    if (pending) {
                        pending = false;
                        discarded.incrementAndGet();
                    }
                }
            };
        }
    }
}
