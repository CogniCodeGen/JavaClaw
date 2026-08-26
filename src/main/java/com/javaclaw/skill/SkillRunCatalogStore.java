package com.javaclaw.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ExtensionStateStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Versioned codec and persistence boundary for one Run's readable skill catalog. */
final class SkillRunCatalogStore {
    private static final String EXTENSION_ID = "framework.skills";
    private static final String STATE_KEY = "readable-catalog";
    private static final int FORMAT_VERSION = 1;

    private final Path skillsRoot;
    private final ObjectMapper json;
    private final ExtensionStateStore states;

    SkillRunCatalogStore(
            Path skillsRoot, ObjectMapper json, ExtensionStateStore states) {
        this.skillsRoot = Objects.requireNonNull(skillsRoot, "skillsRoot")
                .toAbsolutePath().normalize();
        this.json = Objects.requireNonNull(json, "json");
        this.states = Objects.requireNonNull(states, "states");
    }

    SkillRunCatalogSnapshot loadOrCapture(
            RunId runId, Supplier<SkillRunCatalogSnapshot> capture) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(capture, "capture");
        var existing = states.view(runId).get(EXTENSION_ID, STATE_KEY);
        if (existing.isPresent()) return decode(existing.get());
        SkillRunCatalogSnapshot snapshot = Objects.requireNonNull(
                capture.get(), "captured skill catalog");
        states.put(runId, EXTENSION_ID, STATE_KEY, FORMAT_VERSION, encode(snapshot));
        return snapshot;
    }

    private ObjectNode encode(SkillRunCatalogSnapshot snapshot) {
        ObjectNode root = json.createObjectNode();
        root.put("formatVersion", FORMAT_VERSION);
        ObjectNode groups = root.putObject("groups");
        groups.put("constrained", snapshot.groupsConstrained());
        ArrayNode values = groups.putArray("values");
        new TreeSet<>(snapshot.groups()).forEach(values::add);

        ArrayNode skills = root.putArray("skills");
        for (SkillPromptRenderer.CatalogSkill skill : snapshot.catalog().skills()) {
            ObjectNode encoded = skills.addObject();
            encoded.put("name", skill.name());
            encoded.put("lookupKey", skill.lookupKey());
            encoded.put("displayName", skill.displayName());
            encoded.put("category", skill.category());
            encoded.put("description", skill.description());
            encoded.put("content", skill.content());
            if (skill.directory() == null) encoded.putNull("directory");
            else encoded.put("directory", relativeDirectory(skill.directory()));
            ArrayNode references = encoded.putArray("references");
            for (SkillPromptRenderer.CatalogReference reference : skill.references()) {
                references.addObject()
                        .put("name", reference.name())
                        .put("capturedSize", reference.capturedSize());
            }
        }

        ArrayNode bundles = root.putArray("bundles");
        for (SkillPromptRenderer.CatalogBundle bundle : snapshot.catalog().bundles()) {
            ObjectNode encoded = bundles.addObject();
            encoded.put("name", bundle.name());
            encoded.put("description", bundle.description());
            ArrayNode members = encoded.putArray("members");
            bundle.members().forEach(members::add);
        }
        return root;
    }

    private SkillRunCatalogSnapshot decode(JsonNode root) {
        requireObject(root, "skill catalog state");
        if (root.path("formatVersion").asInt(-1) != FORMAT_VERSION) {
            throw new IllegalStateException("unsupported skill catalog state version: "
                    + root.path("formatVersion").asText("missing"));
        }
        JsonNode groupsNode = requireObject(root.get("groups"), "groups");
        boolean constrained = requiredBoolean(groupsNode, "constrained");
        Set<String> groups = new TreeSet<>();
        for (JsonNode value : requireArray(groupsNode.get("values"), "groups.values")) {
            String group = requiredText(value, "groups.values[]", false);
            if (!groups.add(group)) {
                throw new IllegalStateException("groups.values contains a duplicate: " + group);
            }
        }

        List<SkillPromptRenderer.CatalogSkill> skills = new ArrayList<>();
        for (JsonNode value : requireArray(root.get("skills"), "skills")) {
            JsonNode skill = requireObject(value, "skills[]");
            List<SkillPromptRenderer.CatalogReference> references = new ArrayList<>();
            for (JsonNode referenceValue : requireArray(
                    skill.get("references"), "skills[].references")) {
                JsonNode reference = requireObject(
                        referenceValue, "skills[].references[]");
                String referenceName = requiredText(
                        reference.get("name"), "reference.name", false);
                if (!referenceName.equals(
                        SkillPromptRenderer.normalizeReferenceName(referenceName))) {
                    throw new IllegalStateException(
                            "invalid persisted reference name: " + referenceName);
                }
                references.add(new SkillPromptRenderer.CatalogReference(
                        referenceName, requiredLong(reference, "capturedSize")));
            }
            Path directory = decodeDirectory(skill.get("directory"));
            if (directory == null && !references.isEmpty()) {
                throw new IllegalStateException(
                        "persisted references require a managed skill directory");
            }
            skills.add(new SkillPromptRenderer.CatalogSkill(
                    requiredText(skill.get("name"), "skill.name", false),
                    requiredText(skill.get("lookupKey"), "skill.lookupKey", false),
                    requiredText(skill.get("displayName"), "skill.displayName", false),
                    requiredText(skill.get("category"), "skill.category", true),
                    requiredText(skill.get("description"), "skill.description", true),
                    requiredText(skill.get("content"), "skill.content", true),
                    directory, references));
        }

        List<SkillPromptRenderer.CatalogBundle> bundles = new ArrayList<>();
        for (JsonNode value : requireArray(root.get("bundles"), "bundles")) {
            JsonNode bundle = requireObject(value, "bundles[]");
            List<String> members = new ArrayList<>();
            for (JsonNode member : requireArray(bundle.get("members"), "bundle.members")) {
                members.add(requiredText(member, "bundle.members[]", false));
            }
            bundles.add(new SkillPromptRenderer.CatalogBundle(
                    requiredText(bundle.get("name"), "bundle.name", false),
                    requiredText(bundle.get("description"), "bundle.description", true),
                    members));
        }
        return new SkillRunCatalogSnapshot(constrained, groups,
                new SkillPromptRenderer.CatalogSnapshot(skills, bundles));
    }

    private String relativeDirectory(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(skillsRoot) || normalized.equals(skillsRoot)) {
            throw new IllegalStateException(
                    "skill directory is outside the managed root: " + normalized);
        }
        return skillsRoot.relativize(normalized).toString().replace('\\', '/');
    }

    private Path decodeDirectory(JsonNode value) {
        if (value == null || value.isNull()) return null;
        String stored = requiredText(value, "skill.directory", false).replace('\\', '/');
        Path relative = Path.of(stored).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isBlank()) {
            throw new IllegalStateException("invalid persisted skill directory: " + stored);
        }
        Path resolved = skillsRoot.resolve(relative).normalize();
        if (!resolved.startsWith(skillsRoot) || resolved.equals(skillsRoot)) {
            throw new IllegalStateException("persisted skill directory escapes the managed root");
        }
        return resolved;
    }

    private static JsonNode requireObject(JsonNode value, String name) {
        if (value == null || !value.isObject()) {
            throw new IllegalStateException(name + " must be an object");
        }
        return value;
    }

    private static Iterable<JsonNode> requireArray(JsonNode value, String name) {
        if (value == null || !value.isArray()) {
            throw new IllegalStateException(name + " must be an array");
        }
        return value;
    }

    private static boolean requiredBoolean(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException(field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static long requiredLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || value.longValue() < 0) {
            throw new IllegalStateException(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static String requiredText(JsonNode value, String name, boolean allowEmpty) {
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException(name + " must be text");
        }
        String text = value.textValue();
        if (!allowEmpty && text.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return text;
    }
}
