package com.javaclaw.plugins.deliverance;

import java.util.Map;
import java.util.List;

/** Deliverance logical-service payloads carried inside the generic service-plugin protocol. */
final class DeliveranceServiceProtocol {
    private DeliveranceServiceProtocol() { }

    record LoadRequest(String profileId, Protocol.StartupConfig startup) { }
    record UnloadRequest(String profileId) { }
    record ChatRequest(String profileId, Protocol.ChatRequest request) { }
    record EmbeddingRequest(String profileId, Protocol.EmbeddingRequest request) { }
    record PublishRequest(String alias, String profileId, boolean enabled) { }
    record ProfileRegistration(String profileId, Protocol.StartupConfig startup) { }
    record CatalogSync(List<ProfileRegistration> profiles, Map<String, String> aliases) {
        CatalogSync {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        }
    }
    record LoggingConfigure(boolean enabled) { }
    record LoggingStatus(boolean enabled) { }
    record GatewayConfigure(boolean enabled) { }
    record GatewayStatus(boolean enabled) { }
    record ModelStatus(String profileId, String name, String kind, boolean loaded,
                       int actualContextLength, int actualEmbeddingDimensions,
                       String selectedBackend, java.util.Set<String> capabilities) { }
    record LoadResponse(ModelStatus model) { }
    record ErrorUsage(Protocol.Usage usage) { }
}
