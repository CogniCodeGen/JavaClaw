package com.javaclaw.skill;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.util.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Captures the immutable, redacted skill view shared by every disclosure level in one Run. */
final class SkillCatalogSnapshotFactory {
    private static final Logger log = LoggerFactory.getLogger(SkillCatalogSnapshotFactory.class);
    private static final int MAX_DESCRIPTION_CODE_POINTS = 80;
    private static final int MAX_CATEGORY_CODE_POINTS = 32;

    private final SkillPromptRenderer.Source source;
    private final AgentConfig settings;

    SkillCatalogSnapshotFactory(SkillPromptRenderer.Source source, AgentConfig settings) {
        this.source = Objects.requireNonNull(source, "source");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    SkillPromptRenderer.CatalogSnapshot capture(
            Set<String> availableGroups, boolean readable) {
        List<SkillPromptRenderer.CatalogSkill> skills = source.getActiveSkills(availableGroups)
                .stream()
                .filter(skill -> !hasSensitiveName(skill))
                .sorted(java.util.Comparator.comparing(Skill::getName)
                        .thenComparing(skill -> skill.getId() == null ? "" : skill.getId()))
                .map(skill -> captureSkill(skill, readable))
                .toList();
        java.util.Map<String, SkillPromptRenderer.CatalogSkill> byName =
                new java.util.LinkedHashMap<>();
        skills.forEach(skill -> byName.putIfAbsent(skill.name(), skill));
        List<SkillPromptRenderer.CatalogBundle> bundles = settings.isSkillBundlesEnabled()
                ? captureBundles(byName) : List.of();
        return new SkillPromptRenderer.CatalogSnapshot(skills, bundles);
    }

    SkillPromptRenderer.CatalogSkill captureSkill(Skill skill, boolean readable) {
        String name = skill.getName() == null ? "" : skill.getName();
        boolean exactLookup = isCatalogSafeName(name);
        String lookupKey = exactLookup ? name : catalogAlias(skill);
        String displayName = exactLookup ? name
                : truncateCodePoints(sanitizeSingleLine(name), 56) + "…";
        String category = truncateCodePoints(
                redactCatalogValue(skill.getCategory()), MAX_CATEGORY_CODE_POINTS);
        String description = skill.getDescription() == null ? "" : skill.getDescription().strip();
        if (SensitiveDataRedactor.containsLikelyCredential(description)) {
            description = "[描述已隐藏]";
        }
        String content = "";
        List<SkillPromptRenderer.CatalogReference> references = List.of();
        if (readable) {
            String sourceContent = skill.getContent() == null ? "" : skill.getContent();
            content = SensitiveDataRedactor.containsLikelyCredential(sourceContent)
                    ? "[技能正文包含疑似凭据，系统已阻止载入]"
                    : sourceContent;
            references = captureReferences(skill);
        }
        return new SkillPromptRenderer.CatalogSkill(name, lookupKey, displayName, category,
                truncateCodePoints(sanitizeSingleLine(description), MAX_DESCRIPTION_CODE_POINTS),
                content, skill.getDirectory(), references);
    }

    private List<SkillPromptRenderer.CatalogBundle> captureBundles(
            java.util.Map<String, SkillPromptRenderer.CatalogSkill> byName) {
        return source.getEnabledBundles().stream()
                .filter(bundle -> bundle.name != null)
                .filter(bundle -> !SensitiveDataRedactor.containsLikelyCredential(bundle.name))
                .sorted(java.util.Comparator.comparing(bundle -> bundle.name))
                .map(bundle -> new SkillPromptRenderer.CatalogBundle(
                        truncateCodePoints(redactCatalogValue(bundle.name),
                                SkillManager.MAX_SKILL_NAME_CODE_POINTS),
                        truncateCodePoints(redactDescription(bundle.description),
                                MAX_DESCRIPTION_CODE_POINTS),
                        bundle.skills.stream().map(member -> {
                                    if (SensitiveDataRedactor.containsLikelyCredential(member)) {
                                        return "[已隐藏]";
                                    }
                                    SkillPromptRenderer.CatalogSkill skill = byName.get(member);
                                    return skill == null ? null
                                            : SkillPromptRenderer.catalogReference(skill);
                                })
                                .filter(Objects::nonNull)
                                .filter(member -> !member.isBlank()).distinct().toList()))
                .filter(bundle -> !bundle.members().isEmpty())
                .toList();
    }

    private static List<SkillPromptRenderer.CatalogReference> captureReferences(Skill skill) {
        if (!skill.hasReferences()) return List.of();
        List<SkillPromptRenderer.CatalogReference> captured = new ArrayList<>();
        Path references = skill.getDirectory().resolve(Skill.REFERENCES_DIR)
                .toAbsolutePath().normalize();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(references)) {
            for (Path file : files) {
                String filename = file.getFileName().toString();
                if (SensitiveDataRedactor.containsLikelyCredential(filename)) continue;
                Path target = SkillPromptRenderer.resolveReference(references, filename);
                if (target == null) continue;
                try {
                    captured.add(new SkillPromptRenderer.CatalogReference(
                            filename, Files.size(target)));
                } catch (IOException failure) {
                    log.warn("捕获技能参考文档快照失败，已从本轮索引排除: {} ({})",
                            target, failure.toString());
                }
            }
        } catch (IOException failure) {
            log.warn("捕获技能参考目录快照失败: {} ({})", references, failure.toString());
        }
        captured.sort(java.util.Comparator.comparing(
                SkillPromptRenderer.CatalogReference::name));
        return List.copyOf(captured);
    }

    private static boolean hasSensitiveName(Skill skill) {
        return SensitiveDataRedactor.containsLikelyCredential(skill.getName());
    }

    private static String redactCatalogValue(String value) {
        if (value == null) return "";
        return SensitiveDataRedactor.containsLikelyCredential(value) ? "[已隐藏]" : value;
    }

    private static String redactDescription(String description) {
        if (description == null) return "";
        return SensitiveDataRedactor.containsLikelyCredential(description)
                ? "[描述包含疑似凭据，已隐藏]" : description.strip();
    }

    private static boolean isCatalogSafeName(String name) {
        return name != null && !name.isBlank() && !name.strip().equals("*")
                && name.codePointCount(0, name.length())
                <= SkillManager.MAX_SKILL_NAME_CODE_POINTS
                && name.codePoints().noneMatch(Character::isISOControl);
    }

    private static String catalogAlias(Skill skill) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String identity = (skill.getId() == null ? "" : skill.getId())
                    + "\u0000" + (skill.getName() == null ? "" : skill.getName());
            return "skill:" + HexFormat.of().formatHex(
                    digest.digest(identity.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String sanitizeSingleLine(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) safe.append(' ');
            else safe.appendCodePoint(codePoint);
        });
        return safe.toString().strip();
    }

    private static String truncateCodePoints(String value, int maxCodePoints) {
        if (value == null || value.isEmpty()) return "";
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
