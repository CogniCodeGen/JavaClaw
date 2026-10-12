package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.spi.EffectReceiptV1;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskContractV3JsonTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void explicitEncodingPreservesEveryFrozenField() throws Exception {
        var contract = new TaskContractV3(3, "读取页面后点击并观察指定应用", List.of(
                new TaskCriterionV3("read", "读取正文", "browser.observe", CapabilityMetadata.TargetKind.URL,
                        "https://example.com", EffectReceiptV1.Status.OBSERVED, "目标标题", List.of("实际片段")),
                new TaskCriterionV3("click", "在当前页面点击", "browser.click", CapabilityMetadata.TargetKind.URL,
                        "https://example.com", EffectReceiptV1.Status.ACCEPTED, "", List.of(),
                        TaskCriterionV3.BrowserTargetPhase.INPUT_PAGE),
                new TaskCriterionV3("observe", "观察通讯录", "desktop.observe",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "com.example.notes",
                        EffectReceiptV1.Status.OBSERVED, "通讯录")), true, true, "definition",
                List.of("SOURCE_BOUND"), List.of("用户选择"),
                TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT, TaskContractV3.IntentStatus.RESOLVED);
        assertEquals(JSON.valueToTree(contract), contract.toJson());
        assertEquals(contract, JSON.treeToValue(contract.toJson(), TaskContractV3.class));
        var independent = contract.toJson();
        independent.putArray("criteria");
        assertEquals(3, contract.toJson().path("criteria").size());
    }

    @Test
    void questionAndUnresolvedContractsKeepTheirDistinctIntent() throws Exception {
        for (var contract : List.of(TaskContractV3.unknown("待澄清"),
                new TaskContractV3(3, "解释问题", List.of(), false, true, "model"))) {
            assertEquals(JSON.valueToTree(contract), contract.toJson());
            assertEquals(contract, JSON.treeToValue(contract.toJson(), TaskContractV3.class));
        }
    }
}
