package com.javaclaw.skill;

import java.util.Set;

/** Durable, immutable model-visible skill catalog captured for one framework Run. */
record SkillRunCatalogSnapshot(
        boolean groupsConstrained,
        Set<String> groups,
        SkillPromptRenderer.CatalogSnapshot catalog) {
    SkillRunCatalogSnapshot {
        groups = Set.copyOf(groups == null ? Set.of() : groups);
        catalog = java.util.Objects.requireNonNull(catalog, "catalog");
    }
}
