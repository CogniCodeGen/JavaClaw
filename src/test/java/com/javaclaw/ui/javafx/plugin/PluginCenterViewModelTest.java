package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.plugin.PluginManagementApplicationService.Catalog;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Plugin;
import com.javaclaw.application.plugin.PluginManagementApplicationService.State;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PluginCenterViewModelTest {

    @Test
    void filtersNameAndDescriptionCaseInsensitively() {
        PluginCenterViewModel model = new PluginCenterViewModel();
        model.apply(new Catalog(List.of(
                plugin("alpha", "Alpha Tool", "Messaging"),
                plugin("beta", "Beta", "Search Provider"))));

        model.queryProperty().set("search");
        assertEquals(List.of("beta"), model.filteredPlugins().stream().map(Plugin::id).toList());

        model.queryProperty().set("ALPHA");
        assertEquals(List.of("alpha"), model.filteredPlugins().stream().map(Plugin::id).toList());
    }

    @Test
    void clearsSelectionRemovedByCatalogRefresh() {
        PluginCenterViewModel model = new PluginCenterViewModel();
        model.apply(new Catalog(List.of(plugin("alpha", "Alpha", ""))));
        model.select("alpha");

        model.apply(new Catalog(List.of()));

        assertNull(model.selectedIdProperty().get());
    }

    @Test
    void mapsFailuresToVisibleStatus() {
        PluginCenterViewModel model = new PluginCenterViewModel();
        model.showFailure("刷新失败", new IOException("disk offline"));
        assertEquals("disk offline", model.errorProperty().get());
        assertEquals("刷新失败：disk offline", model.statusProperty().get());
    }

    private static Plugin plugin(String id, String name, String description) {
        return new Plugin(id, name, "1", description, List.of(), List.of(),
                List.of(), List.of(), State.STOPPED, "");
    }
}
