package com.javaclaw.launcher;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopFeatureCatalogTest {
    @Test
    void everyPublishedDesktopDomainHasUniqueBlackBoxEvidence() {
        var pages = DesktopFeatureCatalog.pages();
        var features = DesktopFeatureCatalog.features();
        assertEquals(
                pages.size(),
                new HashSet<>(pages.stream()
                                .map(DesktopFeatureCatalog.Page::title)
                                .toList())
                        .size());
        assertEquals(
                features.size(),
                new HashSet<>(features.stream()
                                .map(DesktopFeatureCatalog.Feature::id)
                                .toList())
                        .size());
        var destinations = new HashSet<>(
                pages.stream().map(DesktopFeatureCatalog.Page::title).toList());
        destinations.add("main");
        for (var feature : features) {
            assertTrue(destinations.contains(feature.page()), feature.id());
            assertFalse(feature.entry().isBlank(), feature.id());
            assertFalse(feature.action().isBlank(), feature.id());
            assertFalse(feature.stateChange().isBlank(), feature.id());
            assertFalse(feature.persistentResult().isBlank(), feature.id());
        }
        for (var page : pages) {
            assertTrue(features.stream().anyMatch(value -> value.id().equals(page.featureId())), page.title());
            assertFalse(page.requiredText().isEmpty(), page.title());
        }
        assertTrue(features.stream().noneMatch(value -> value.id().contains("ollama")));
        assertTrue(features.stream().noneMatch(value -> value.id().contains("deliverance")));
    }
}
