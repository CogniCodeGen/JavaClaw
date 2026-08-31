package com.javaclaw.server.model;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudAdapterBootstrapTest {
    @Test
    void realProviderAdaptersInitializeWithSyntheticKeysWithoutNetworkCalls() {
        // 不仅替换 ChatModel：必须构造实际发行依赖，及早发现 SDK/Jackson/Kotlin 的二进制不兼容。
        try (var gateway = SpringAiCloudModelGateway.fromEnvironment(Map.of(
                "OPENAI_API_KEY", "synthetic-openai-key",
                "ANTHROPIC_API_KEY", "synthetic-anthropic-key",
                "GOOGLE_API_KEY", "synthetic-google-key"))) {
            assertTrue(gateway.descriptors().stream().allMatch(CloudModelDescriptor::configured));
        }
    }
}
