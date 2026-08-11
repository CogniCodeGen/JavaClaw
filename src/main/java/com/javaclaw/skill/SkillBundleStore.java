package com.javaclaw.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Workspace-scoped JSON store for named groups of skills. */
final class SkillBundleStore {

    private static final Logger log = LoggerFactory.getLogger(SkillBundleStore.class);
    private static final String FILE_NAME = "bundles.json";

    private final Path file;
    private final ObjectMapper mapper;
    private final List<SkillBundle> bundles = new ArrayList<>();

    SkillBundleStore(Path skillsDirectory, ObjectMapper mapper) {
        this.file = Objects.requireNonNull(skillsDirectory, "skillsDirectory").resolve(FILE_NAME);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        reload();
    }

    void reload() {
        bundles.clear();
        if (!Files.exists(file)) {
            return;
        }
        try {
            SkillBundle[] loaded = mapper.readValue(file.toFile(), SkillBundle[].class);
            for (SkillBundle bundle : loaded) {
                if (bundle != null && bundle.name != null && !bundle.name.isBlank()) {
                    bundles.add(bundle);
                }
            }
            log.info("已加载 {} 个技能包", bundles.size());
        } catch (IOException failure) {
            log.warn("加载技能包配置失败: {}", file, failure);
        }
    }

    List<SkillBundle> all() {
        return new ArrayList<>(bundles);
    }

    List<SkillBundle> enabled() {
        return bundles.stream().filter(bundle -> bundle.enabled).toList();
    }

    SkillBundle enabled(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = name.strip();
        return bundles.stream()
                .filter(bundle -> bundle.enabled && target.equals(bundle.name))
                .findFirst()
                .orElse(null);
    }

    void save(List<SkillBundle> replacements) {
        bundles.clear();
        if (replacements != null) {
            bundles.addAll(replacements);
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), bundles);
            log.info("已保存 {} 个技能包", bundles.size());
        } catch (IOException failure) {
            log.error("保存技能包配置失败", failure);
        }
    }
}
