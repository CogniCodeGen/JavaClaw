package com.javaclaw.server.rpc;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.model.ProviderModelDiscoveryAdapter;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ProviderModelDiscoveryService;
import com.javaclaw.server.persistence.ProviderService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelDiscoveryRpcHandlersTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 同一长连接的顺序请求只注册一次关闭回调() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        CanonicalJson json = new CanonicalJson();
        ProviderService providers = new ProviderService(database, ignored -> true, json, Clock.systemUTC());
        ProviderModelDiscoveryAdapter adapter =
                new ProviderModelDiscoveryAdapter(ignored -> Optional.empty(), Clock.systemUTC());
        try (ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(providers, adapter);
                SessionSecretChannel session = SessionSecretChannel.open()) {
            ProviderModelDiscoveryRpcHandlers handlers = new ProviderModelDiscoveryRpcHandlers(service, json);

            assertTrue(handlers.registerClose(session));
            for (int request = 0; request < 1_024; request++) {
                assertFalse(handlers.registerClose(session));
            }

            session.close();
            assertTrue(handlers.registerClose(session));
        }
    }
}
