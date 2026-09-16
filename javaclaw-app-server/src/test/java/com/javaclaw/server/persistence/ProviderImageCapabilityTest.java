package com.javaclaw.server.persistence;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderImageSupport;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderImageCapabilityTest {
    @TempDir
    Path directory;

    @Test
    void 图片能力只取精确端点版本和精确模型的明确声明() {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        CanonicalJson json = new CanonicalJson();
        ProviderService service = new ProviderService(database, ignored -> false, json, Clock.systemUTC());
        ProviderEndpointSpec original = spec(List.of(
                model("vision", ProviderImageSupport.SUPPORTED),
                model("text", ProviderImageSupport.UNSUPPORTED),
                model("unknown", ProviderImageSupport.UNKNOWN)));
        ProviderEndpoint first =
                service.create(identity(json, original, 0), "provider", original, ProviderLifecycle.ACTIVE);
        assertTrue(service.probe(new ProviderRef(first.id(), 1, "vision"))
                .capabilities()
                .images());
        assertFalse(service.probe(new ProviderRef(first.id(), 1, "text"))
                .capabilities()
                .images());
        assertFalse(service.probe(new ProviderRef(first.id(), 1, "unknown"))
                .capabilities()
                .images());
        assertFalse(service.probe(new ProviderRef(first.id(), 1, "missing"))
                .capabilities()
                .images());

        ProviderEndpointSpec updated = spec(List.of(model("vision", ProviderImageSupport.UNSUPPORTED)));
        ProviderEndpoint second =
                service.update(identity(json, updated, 1), first.id(), updated, ProviderLifecycle.ACTIVE);
        assertFalse(service.probe(new ProviderRef(second.id(), 2, "vision"))
                .capabilities()
                .images());
        assertTrue(service.probe(new ProviderRef(first.id(), 1, "vision"))
                .capabilities()
                .images());
        assertEquals(
                ProviderImageSupport.SUPPORTED,
                service.require(first.id(), 1).spec().models().getFirst().imageSupport());
    }

    @Test
    void 只有用途而没有图片声明时所有适配器都保持保守能力() {
        for (ProviderAdapter adapter : ProviderAdapter.values()) {
            assertFalse(ProviderService.capabilities(adapter, Set.of(ProviderModelPurpose.CHAT))
                    .images());
            assertTrue(ProviderService.capabilities(
                            adapter, Set.of(ProviderModelPurpose.CHAT), ProviderImageSupport.SUPPORTED)
                    .images());
            assertFalse(ProviderService.capabilities(
                            adapter, Set.of(ProviderModelPurpose.EMBEDDING), ProviderImageSupport.SUPPORTED)
                    .images());
        }
    }

    private static ProviderModelSpec model(String id, ProviderImageSupport support) {
        return new ProviderModelSpec(id, id, Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty(), support);
    }

    private static ProviderEndpointSpec spec(List<ProviderModelSpec> models) {
        return new ProviderEndpointSpec(
                "Images",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:11434/v1")),
                ProviderAuthentication.NONE,
                models,
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private static CommandIdentity identity(CanonicalJson json, ProviderEndpointSpec spec, long revision) {
        return new CommandIdentity(
                revision == 0 ? "provider/create" : "provider/update",
                "images-" + revision,
                revision,
                json.encode(spec).sha256());
    }
}
