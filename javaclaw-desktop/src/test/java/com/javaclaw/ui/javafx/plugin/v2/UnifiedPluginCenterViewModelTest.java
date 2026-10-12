package com.javaclaw.ui.javafx.plugin.v2;

import com.javaclaw.application.plugin.v2.PluginManagementService.PluginSummary;
import com.javaclaw.application.plugin.v2.PluginManagementService.StartupPolicy;
import com.javaclaw.application.plugin.v2.PluginManagementService.State;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnifiedPluginCenterViewModelTest {
    @Test void filtersByCapabilityAndSearchWithoutConfusingEnablementWithProcessState() {
        var model = new UnifiedPluginCenterViewModel();
        model.apply(List.of(plugin("alpha", "工具助手", "搜索网页", Set.of("tools"), true, State.STOPPED),
                plugin("beta", "本地模型", "本地推理", Set.of("models", "context"), false, State.READY)));
        assertEquals(List.of("全部能力", "context", "models", "tools"), model.capabilityFilters());
        assertEquals("beta", model.filtered("BETA", "models").getFirst().id());
        assertEquals(0, model.filtered("网页", "models").size());
        assertEquals("alpha", model.filtered("网页", null).getFirst().id());
        assertEquals("已停止", UnifiedPluginCenterViewModel.stateText(model.plugins().getFirst().state()));
        assertEquals(false, model.plugins().getLast().enabled());
        assertEquals("运行正常", UnifiedPluginCenterViewModel.stateText(model.plugins().getLast().state()));
    }

    private static PluginSummary plugin(String id, String name, String description,
                                        Set<String> capabilities, boolean enabled, State state) {
        return new PluginSummary(id, name, "1.0.0", description, "示例发布者", "a".repeat(64), true,
                enabled, StartupPolicy.MANUAL, state, 1, capabilities, "");
    }
}
