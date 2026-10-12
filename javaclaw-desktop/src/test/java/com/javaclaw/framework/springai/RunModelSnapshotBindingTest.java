package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.spi.ExternalPluginLock;
import com.javaclaw.inference.api.InferenceChatResponse;
import com.javaclaw.inference.api.InferenceProviderSnapshot;
import com.javaclaw.inference.api.InferenceUsage;
import com.javaclaw.inference.api.LocalInferenceGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RunModelSnapshotBindingTest {
    @Test void managedModelsUseOnlyTheFrozenGatewayIncludingAnEmptyProviderList() {
        AtomicReference<List<InferenceProviderSnapshot>> captured = new AtomicReference<>();
        LocalInferenceGateway pinned = (LocalInferenceGateway) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{LocalInferenceGateway.class}, (proxy, method, args) -> {
                    assertEquals("chat", method.getName());
                    return new InferenceChatResponse("request", "fixture", "pinned answer", "", List.of(),
                            InferenceChatResponse.FinishReason.STOP, new InferenceUsage(1, 2), Duration.ZERO, Duration.ZERO);
                });
        LocalInferenceGateway current = (LocalInferenceGateway) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{LocalInferenceGateway.class}, (proxy, method, args) -> {
                    assertEquals("forSnapshots", method.getName());
                    captured.set(List.copyOf((List<InferenceProviderSnapshot>) args[0]));
                    return pinned;
                });
        var model = new ManagedInferenceChatModel(current, UUID.randomUUID(), false, Duration.ofSeconds(3), new ObjectMapper());
        var lock = new ExternalPluginLock("workspace-a", "model-provider", "a".repeat(64), 7, List.of());

        ChatModel frozen = RunModelSnapshotBinding.bind(model, List.of(lock));
        assertEquals("pinned answer", frozen.call(new Prompt("hello")).getResult().getOutput().getText());
        assertEquals(List.of(new InferenceProviderSnapshot("workspace-a", "model-provider", "a".repeat(64), 7)), captured.get());
        RunModelSnapshotBinding.bind(model, List.of());
        assertEquals(List.of(), captured.get());
    }

    @Test void modelsWithoutAPluginGatewayKeepTheirExistingModelInstance() {
        ChatModel cloudModel = prompt -> null;
        assertSame(cloudModel, RunModelSnapshotBinding.bind(cloudModel, List.of()));
    }
}
