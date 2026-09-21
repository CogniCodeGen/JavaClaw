package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderImageSupport;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupModelSelectionTest {
    @Test
    void 保存目录的多用途维度和图片声明优先远程候选且取消重选不丢失() {
        var selection = new ProviderSetupModelSelection();
        var saved = new ProviderModelSpec(
                "both",
                "自定义名称",
                Set.of(ProviderModelPurpose.CHAT, ProviderModelPurpose.EMBEDDING),
                OptionalInt.of(1536),
                ProviderImageSupport.SUPPORTED);
        selection.seed(List.of(saved));
        selection.candidates(List.of(new ProviderModelDiscoveryCandidate(
                "both", "远程名称", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())));
        selection.addManual("both", ProviderModelPurpose.CHAT);
        selection.select("both", false);
        selection.select("both", true);
        assertEquals(List.of(saved), selection.selectedModels());
    }

    @Test
    void 名称不推断用途且批量确认后纯向量目录有效() {
        var selection = new ProviderSetupModelSelection();
        selection.candidates(List.of(new ProviderModelDiscoveryCandidate(
                "text-embedding-latest", "embedding", Set.of(), OptionalInt.empty())));
        selection.select("text-embedding-latest", true);
        assertThrows(IllegalArgumentException.class, selection::selectedModels);
        selection.selectedPurposes(Set.of(ProviderModelPurpose.EMBEDDING));
        selection.dimensions("text-embedding-latest", "1024");
        var model = selection.selectedModels().getFirst();
        assertEquals(Set.of(ProviderModelPurpose.EMBEDDING), model.purposes());
        assertEquals(OptionalInt.of(1024), model.embeddingDimensions());
    }

    @Test
    void 手动配置可明确修复已发现但未知用途的同名模型() {
        var selection = new ProviderSetupModelSelection();
        selection.candidates(
                List.of(new ProviderModelDiscoveryCandidate("opaque", "远程显示名", Set.of(), OptionalInt.empty())));
        selection.addManual("opaque", ProviderModelPurpose.EMBEDDING);
        assertTrue(selection.selected("opaque"));
        assertEquals("远程显示名", selection.selectedModels().getFirst().displayName());
        assertEquals(
                Set.of(ProviderModelPurpose.EMBEDDING),
                selection.selectedModels().getFirst().purposes());
    }

    @Test
    void 搜索分别匹配完整模型ID和显示名称且不改变选择() {
        var selection = new ProviderSetupModelSelection();
        selection.candidates(List.of(
                new ProviderModelDiscoveryCandidate("Vendor/Alpha-2026", "易读名称", Set.of(), OptionalInt.empty()),
                new ProviderModelDiscoveryCandidate("vendor/beta", "另一个模型", Set.of(), OptionalInt.empty())));
        selection.select("Vendor/Alpha-2026", true);
        assertEquals(
                "Vendor/Alpha-2026",
                selection.matching(" ALPHA-2026 ").getFirst().id());
        assertEquals("Vendor/Alpha-2026", selection.matching("易读").getFirst().id());
        assertTrue(selection.matching("不存在的ID").isEmpty());
        assertEquals(1, selection.selectedCount());
        assertEquals("Vendor/Alpha-2026", selection.selectedDrafts().getFirst().id());
        assertTrue(selection.selectedDrafts().getFirst().purposes().isEmpty());
    }

    @Test
    void 无效维度不会静默丢弃而是阻止最终提交() {
        var selection = new ProviderSetupModelSelection();
        selection.addManual("embedding", ProviderModelPurpose.EMBEDDING);
        selection.dimensions("embedding", "不是整数");
        assertThrows(IllegalArgumentException.class, selection::selectedModels);
        selection.dimensions("embedding", "0");
        assertThrows(IllegalArgumentException.class, selection::selectedModels);
        selection.dimensions("embedding", "");
        assertEquals(OptionalInt.empty(), selection.selectedModels().getFirst().embeddingDimensions());
    }
}
