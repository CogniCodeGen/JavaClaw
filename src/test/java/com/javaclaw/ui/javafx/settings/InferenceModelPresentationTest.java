package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.application.inference.InferenceSystemProfilePort;
import com.javaclaw.inference.api.InferenceModelProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceModelPresentationTest {

    @Test
    void capacityDistinguishesAvailableMemoryFromTheNativeModelBudget() {
        var capacity = new InferenceSystemProfilePort.SystemCapacity(
                10, gib(32), gib(12), gib(19.2), gib(6.72), gib(12.48),
                gib(9.6), 6, true, true);

        String text = InferenceModelPresentation.capacity(capacity);

        assertTrue(text.contains("当前可用 12.00 GiB"));
        assertTrue(text.contains("Native 12.48 GiB"));
        assertTrue(text.contains("安全模型预算 9.60 GiB"));
    }

    @Test
    void capacityDoesNotPresentAnUnreliableRawValueAsAvailableMemory() {
        var capacity = new InferenceSystemProfilePort.SystemCapacity(
                10, gib(32), 132L * 1024 * 1024, gib(19.2), gib(6.72), gib(12.48),
                gib(12.48), 6, true, false);

        assertTrue(InferenceModelPresentation.capacity(capacity)
                .contains("当前可用 未能可靠读取"));
    }

    @Test
    void serviceConsoleShowsEveryProfileExactlyOnceWithItsStrongestState() {
        UUID loadedId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        InferenceModelProfile loaded = profile(loadedId, assetId, "Qwen", InferenceModelProfile.State.READY);
        InferenceModelProfile failed = profile(failedId, assetId, "Broken", InferenceModelProfile.State.FAILED);
        var snapshot = new InferenceManagementApplicationService.Snapshot(
                List.of(), List.of(), List.of(loaded, failed), Map.of(),
                List.of(
                        new InferenceCatalogPort.PublishedModel(
                                "qwen", loadedId, true, Instant.EPOCH, Instant.EPOCH),
                        new InferenceCatalogPort.PublishedModel(
                                "qwen-alt", loadedId, true, Instant.EPOCH, Instant.EPOCH)),
                InferenceCatalogPort.GatewayConfiguration.defaults(), List.of());
        var statuses = Map.of(loadedId, new InferenceRuntimePort.RuntimeProfileStatus(
                loadedId, true, 4096, 0, "simd", Set.of("chat")));

        var rows = InferenceModelPresentation.serviceModels(snapshot, statuses);

        assertEquals(2, rows.size());
        assertTrue(rows.getFirst().label().startsWith("已加载  Qwen"));
        assertTrue(rows.getFirst().label().contains("simd · 4096 context"));
        assertEquals(List.of("qwen", "qwen-alt"), rows.getFirst().value().aliases());
        assertTrue(rows.get(1).label().startsWith("失败  Broken"));
    }

    private static InferenceModelProfile profile(
            UUID id, UUID assetId, String name, InferenceModelProfile.State state) {
        return new InferenceModelProfile(id, name, InferenceModelProfile.Kind.GENERATION,
                assetId, "deliverance", Map.of(), Map.of(), 4096, 0, state,
                state == InferenceModelProfile.State.FAILED ? "load failed" : "",
                Instant.EPOCH, Instant.EPOCH);
    }

    private static long gib(double value) {
        return Math.round(value * 1024 * 1024 * 1024);
    }
}
