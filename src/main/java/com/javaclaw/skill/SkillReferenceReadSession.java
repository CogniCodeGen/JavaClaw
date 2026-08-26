package com.javaclaw.skill;

import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local search grants for large reference reads within one framework Run. */
final class SkillReferenceReadSession {
    static final String RESOURCE_KEY = "skills.reference-read-session";

    private final ConcurrentHashMap<Location, FileVersion> lines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Location, FileVersion> cursors = new ConcurrentHashMap<>();

    void grantLine(
            SkillPromptRenderer.CatalogSkill skill, String reference,
            int line, FileVersion version) {
        lines.put(new Location(skill.lookupKey(), reference, line), version);
    }

    boolean allowsLine(
            SkillPromptRenderer.CatalogSkill skill, String reference,
            int line, FileVersion current) {
        return allows(lines, new Location(skill.lookupKey(), reference, line), current);
    }

    void grantCursor(
            SkillPromptRenderer.CatalogSkill skill, String reference,
            int cursor, FileVersion version) {
        cursors.put(new Location(skill.lookupKey(), reference, cursor), version);
    }

    boolean allowsCursor(
            SkillPromptRenderer.CatalogSkill skill, String reference,
            int cursor, FileVersion current) {
        return allows(cursors, new Location(skill.lookupKey(), reference, cursor), current);
    }

    private static boolean allows(
            ConcurrentHashMap<Location, FileVersion> grants,
            Location location, FileVersion current) {
        FileVersion granted = grants.get(location);
        if (current.equals(granted)) return true;
        if (granted != null) grants.remove(location, granted);
        return false;
    }

    record FileVersion(long size, FileTime lastModified, Object fileKey) {
        FileVersion {
            if (size < 0) throw new IllegalArgumentException("size must not be negative");
            lastModified = Objects.requireNonNull(lastModified, "lastModified");
        }
    }

    private record Location(String skill, String reference, int position) {
        private Location {
            skill = Objects.requireNonNull(skill, "skill");
            reference = Objects.requireNonNull(reference, "reference");
            if (position < 0) throw new IllegalArgumentException("position must not be negative");
        }
    }
}
