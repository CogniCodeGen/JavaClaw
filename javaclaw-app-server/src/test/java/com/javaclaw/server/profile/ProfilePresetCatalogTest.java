package com.javaclaw.server.profile;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilePresetCatalogTest {
    @Test
    void listsReviewedPresetsWithStableDigestsAndBudgets() {
        ProfilePresetCatalog catalog = new ProfilePresetCatalog();

        assertEquals(
                java.util.List.of("default", "worker", "explorer"),
                catalog.list().stream().map(preset -> preset.id()).toList());
        assertEquals(4, catalog.require("default", 1).defaultBudget().childThreads());
        assertEquals(
                Duration.ofMinutes(10),
                catalog.require("worker", 1).defaultBudget().wallTime());
        assertEquals(0, catalog.require("explorer", 1).defaultBudget().childThreads());
        assertTrue(catalog.list().stream().allMatch(preset -> preset.digest().matches("[0-9a-f]{64}")));
    }

    @Test
    void rejectsUnknownPresetRevision() {
        ProfilePresetCatalog catalog = new ProfilePresetCatalog();

        assertThrows(IllegalArgumentException.class, () -> catalog.require("default", 2));
    }
}
