package com.javaclaw.desktop.settings;

import java.util.Map;
import java.util.Optional;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteSettingsResumeTest {
    @Test
    void 同一页面定义重开保留网站与展开分区并读取新数据() {
        FxTestSupport.run(() -> {
            SiteSettingsTestGateway gateway = new SiteSettingsTestGateway();
            SiteSettingsPage page = new SiteSettingsPage(new TestCoreSettingsGateway(), gateway);
            new Scene((Parent) page.content(), 880, 620);
            page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            page.activate();
            page.content().applyCss();
            table(page).getSelectionModel().select(1);
            TitledPane advanced = (TitledPane) page.content().lookup("#site-advanced");
            advanced.setExpanded(true);
            page.deactivate();
            gateway.sites.set(1, SiteSettingsTestGateway.site("b", "重开后的名称"));
            int before = gateway.requests.size();
            page.activate();
            assertEquals(before + 1, gateway.requests.size());
            assertEquals("b", table(page).getSelectionModel().getSelectedItem().get("id"));
            assertEquals(
                    "重开后的名称", table(page).getSelectionModel().getSelectedItem().get("name"));
            assertTrue(advanced.isExpanded());
            assertTrue(gateway.commands.isEmpty());
            page.dispose();
        });
    }

    @SuppressWarnings("unchecked")
    private static TableView<Map<String, Object>> table(SiteSettingsPage page) {
        return (TableView<Map<String, Object>>) page.content().lookup("#site-list");
    }
}
