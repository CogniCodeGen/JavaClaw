package com.javaclaw.ui.javafx.settings;

import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettingsPanelCatalogTest {

    @Test
    void createsOnlyVisitedPanelsOnceAndClosesThemInReverseCreationOrder() {
        Map<SettingsCategory, AtomicInteger> creations = new EnumMap<>(SettingsCategory.class);
        List<SettingsCategory> closed = new ArrayList<>();
        List<SettingsPanelCatalog.Definition> definitions = new ArrayList<>();
        for (SettingsCategory category : SettingsCategory.values()) {
            creations.put(category, new AtomicInteger());
            definitions.add(new SettingsPanelCatalog.Definition(category, () -> {
                creations.get(category).incrementAndGet();
                return new SettingsPanelCatalog.Panel(category, new Pane(),
                        SettingsPanelActions.none(), () -> { }, () -> closed.add(category));
            }));
        }
        SettingsPanelCatalog catalog = new SettingsPanelCatalog(definitions);

        assertEquals(0, catalog.loadedCount());
        SettingsPanelCatalog.Panel model = catalog.load(SettingsCategory.MODEL);
        SettingsPanelCatalog.Panel agent = catalog.load(SettingsCategory.AGENT);
        assertSame(model, catalog.load(SettingsCategory.MODEL));
        assertEquals(2, catalog.loadedCount());
        assertEquals(1, creations.get(SettingsCategory.MODEL).get());
        assertEquals(1, creations.get(SettingsCategory.AGENT).get());
        assertEquals(0, creations.get(SettingsCategory.EMAIL).get());

        catalog.close();

        assertEquals(List.of(SettingsCategory.AGENT, SettingsCategory.MODEL), closed);
        assertThrows(IllegalStateException.class,
                () -> catalog.load(SettingsCategory.NOTIFICATION));
    }

    @Test
    void rejectsIncompleteDefinitionSetsBeforeAnyFxmlCanBeLoaded() {
        assertThrows(IllegalArgumentException.class,
                () -> new SettingsPanelCatalog(List.of()));
    }
}
