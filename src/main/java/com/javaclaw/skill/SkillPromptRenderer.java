package com.javaclaw.skill;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.util.PathGuard;
import com.javaclaw.util.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 技能的模型可见投影，集中处理渐进式暴露和敏感信息阻断。
 *
 * <p>本对象只读取 {@link Source} 的快照和技能目录，不改变技能状态。它随工作区
 * {@link SkillManager} 创建，无独立线程或关闭协议。</p>
 */
final class SkillPromptRenderer {

    private static final Logger log = LoggerFactory.getLogger(SkillPromptRenderer.class);
    private static final int MAX_REFERENCE_CHARS = 10_000;

    interface Source {
        List<Skill> getEnabledSkills();

        List<Skill> getActiveSkills(Set<String> availableGroups);

        Skill getSkillByName(String name);

        List<SkillBundle> getEnabledBundles();

        SkillBundle getBundle(String name);
    }

    private final Source source;
    private final AgentConfig settings;

    SkillPromptRenderer(Source source, AgentConfig settings) {
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
    }

    String buildCatalog(Set<String> availableGroups) {
        List<Skill> active = source.getActiveSkills(availableGroups).stream()
                .filter(skill -> !hasSensitiveName(skill))
                .toList();
        StringBuilder prompt = new StringBuilder();
        appendSkillCatalog(prompt, active);
        appendBundleCatalog(prompt);
        appendEvolutionNudge(prompt);
        return prompt.toString();
    }

    private void appendSkillCatalog(StringBuilder prompt, List<Skill> active) {
        if (active.isEmpty()) {
            return;
        }
        prompt.append("\n\n## 可用技能目录\n")
                .append("以下是当前已配置的技能清单（仅名称与用途）。当任务与某技能相关时，请遵循对应技能的指令完成工作；\n")
                .append("若清单中列出某技能、但下方未提供其详细指令，且该技能与当前任务相关，\n")
                .append("请调用 skill_read 工具（参数 skill_name 填技能名称）按需拉取其完整指令后再执行；\n")
                .append("技能若列出参考文档，可再用 skill_read 的 path 参数单独拉取某个文档；\n")
                .append("严格项目隔离模式下不执行技能脚本；技能仅提供可审查的流程与参考资料。\n");
        for (Skill skill : active) {
            appendCatalogEntry(prompt, skill);
        }
    }

    private void appendCatalogEntry(StringBuilder prompt, Skill skill) {
        prompt.append("- 【").append(skill.getName()).append("】");
        if (!skill.getCategory().isBlank()) {
            prompt.append("[").append(skill.getCategory()).append("] ");
        }
        if (!skill.getTags().isEmpty()) {
            prompt.append("(").append(String.join("/", skill.getTags())).append(") ");
        }
        String description = skill.getDescription();
        if (description != null && !description.isBlank()) {
            prompt.append(SensitiveDataRedactor.containsLikelyCredential(description)
                    ? "[描述包含疑似凭据，已隐藏]" : description.strip());
        }
        List<String> references = listReferenceFiles(skill);
        if (!references.isEmpty()) {
            prompt.append("；参考文档：").append(String.join("、", references));
        }
        List<String> scripts = listScriptFiles(skill);
        if (!scripts.isEmpty()) {
            prompt.append("；脚本：").append(String.join("、", scripts))
                    .append("（严格隔离下不可执行）");
        }
        prompt.append("\n");
    }

    private void appendBundleCatalog(StringBuilder prompt) {
        if (!settings.isSkillBundlesEnabled()) {
            return;
        }
        List<SkillBundle> bundles = source.getEnabledBundles().stream()
                .filter(bundle -> !SensitiveDataRedactor.containsLikelyCredential(bundle.name))
                .toList();
        if (bundles.isEmpty()) {
            return;
        }
        prompt.append("\n## 可用技能包\n")
                .append("技能包是一组配合使用的技能；任务匹配某包描述时，包内技能将成组注入。\n");
        for (SkillBundle bundle : bundles) {
            prompt.append("- 【").append(bundle.name).append("】")
                    .append(redactDescription(bundle.description))
                    .append("（含：").append(bundle.skills.stream()
                            .map(SkillPromptRenderer::redactCatalogValue)
                            .collect(Collectors.joining("、")))
                    .append("）\n");
        }
    }

    private void appendEvolutionNudge(StringBuilder prompt) {
        if (!settings.isSkillNudgeEnabled()
                || "off".equals(settings.getSkillEvolutionMode())) {
            return;
        }
        prompt.append("\n## 经验沉淀\n")
                .append("若本次完成了非平凡的多步骤工作流、踩坑后找到了可行路径、或被用户纠正了做法，\n")
                .append("请考虑调用 skill_create 把经验沉淀为新技能，或用 skill_patch 把新认知合入相关既有技能（小修优先 patch）。\n");
    }

    String buildEnabledPrompt() {
        return buildSkillsPrompt(source.getEnabledSkills());
    }

    String buildFilteredPrompt(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) {
            return "";
        }
        return buildSkillsPrompt(source.getEnabledSkills().stream()
                .filter(skill -> skillNames.contains(skill.getName()))
                .toList());
    }

    private String buildSkillsPrompt(List<Skill> candidates) {
        List<Skill> visible = candidates.stream()
                .filter(skill -> !hasSensitiveName(skill))
                .toList();
        if (visible.isEmpty()) {
            return "";
        }
        StringBuilder prompt = new StringBuilder(
                "\n\n以下是用户配置的技能指令，请在回答时遵循：\n");
        for (Skill skill : visible) {
            appendSkill(prompt, skill);
        }
        return prompt.toString();
    }

    private void appendSkill(StringBuilder prompt, Skill skill) {
        prompt.append("\n【").append(skill.getName()).append("】\n");
        if (SensitiveDataRedactor.containsLikelyCredential(skill.getContent())) {
            prompt.append("[技能正文包含疑似凭据，系统已阻止载入]\n");
            return;
        }
        prompt.append(skill.getContent()).append("\n");
        appendReferences(prompt, skill);
    }

    String buildSkillDetail(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = name.strip();
        Skill skill = source.getEnabledSkills().stream()
                .filter(candidate -> candidate.getName().equals(target))
                .findFirst()
                .orElse(null);
        if (skill == null || hasSensitiveName(skill)) {
            return null;
        }
        if (SensitiveDataRedactor.containsLikelyCredential(skill.getContent())) {
            return "【" + skill.getName() + "】\n[技能正文包含疑似凭据，系统已阻止载入]";
        }
        StringBuilder detail = new StringBuilder()
                .append("【").append(skill.getName()).append("】\n")
                .append(skill.getContent() == null ? "" : skill.getContent()).append("\n");
        appendReferences(detail, skill);
        return detail.toString();
    }

    String buildReferenceDetail(String name, String relativePath) {
        Skill skill = source.getSkillByName(name);
        if (skill == null || !skill.isEnabled() || skill.getDirectory() == null
                || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path references = skill.getDirectory().resolve(Skill.REFERENCES_DIR)
                .toAbsolutePath().normalize();
        Path target = resolveReference(references, relativePath);
        if (target == null) {
            return null;
        }
        try {
            String text = Files.readString(target, StandardCharsets.UTF_8);
            if (SensitiveDataRedactor.containsLikelyCredential(text)) {
                return "--- " + target.getFileName()
                        + " ---\n[该参考文档包含疑似凭据，系统已阻止载入]";
            }
            return "--- " + target.getFileName() + " ---\n" + truncateReference(text);
        } catch (IOException e) {
            log.warn("读取参考文档失败: {}", target, e);
            return null;
        }
    }

    private static Path resolveReference(Path references, String relativePath) {
        String cleaned = relativePath.strip().replace('\\', '/');
        if (cleaned.startsWith(Skill.REFERENCES_DIR + "/")) {
            cleaned = cleaned.substring(Skill.REFERENCES_DIR.length() + 1);
        }
        Path target = references.resolve(cleaned).normalize();
        if (!target.startsWith(references) || !Files.isRegularFile(target)
                || !SkillFileRepository.isTextFile(target)
                || !PathGuard.isInside(references, target)) {
            return null;
        }
        return target;
    }

    String buildBundlePrompt(String bundleName) {
        SkillBundle bundle = source.getBundle(bundleName);
        if (bundle == null || bundle.skills.isEmpty()
                || SensitiveDataRedactor.containsLikelyCredential(bundle.name)) {
            return "";
        }
        StringBuilder skills = new StringBuilder();
        int loaded = 0;
        for (String skillName : bundle.skills) {
            String detail = buildSkillDetail(skillName);
            if (detail == null) {
                log.warn("技能包「{}」内技能「{}」不存在或未启用，已跳过", bundle.name, skillName);
                continue;
            }
            skills.append("\n").append(detail);
            loaded++;
        }
        if (loaded == 0) {
            return "";
        }
        StringBuilder prompt = new StringBuilder()
                .append("\n\n## 技能包【").append(bundle.name).append("】\n")
                .append("以下 ").append(loaded).append(" 项技能作为一组配合使用：\n")
                .append(skills);
        if (bundle.extraInstructions != null && !bundle.extraInstructions.isBlank()) {
            prompt.append("\n[本包附加指令]\n")
                    .append(SensitiveDataRedactor.containsLikelyCredential(bundle.extraInstructions)
                            ? "[附加指令包含疑似凭据，已隐藏]"
                            : bundle.extraInstructions.strip())
                    .append("\n");
        }
        return prompt.toString();
    }

    private static void appendReferences(StringBuilder prompt, Skill skill) {
        if (!skill.hasReferences()) {
            return;
        }
        String references = loadReferences(skill);
        if (!references.isEmpty()) {
            prompt.append("\n[参考文档]\n").append(references).append("\n");
        }
    }

    private static String loadReferences(Skill skill) {
        Path references = skill.getDirectory().resolve(Skill.REFERENCES_DIR);
        StringBuilder content = new StringBuilder();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(references)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file) || !SkillFileRepository.isTextFile(file)
                        || !PathGuard.isInside(references, file)) {
                    continue;
                }
                content.append("--- ").append(file.getFileName()).append(" ---\n");
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (SensitiveDataRedactor.containsLikelyCredential(text)) {
                    content.append("[该参考文档包含疑似凭据，系统已阻止载入]\n\n");
                    continue;
                }
                content.append(truncateReference(text)).append("\n\n");
            }
        } catch (IOException e) {
            log.warn("读取参考文档失败: {}", references, e);
        }
        return content.toString();
    }

    private static List<String> listScriptFiles(Skill skill) {
        if (!skill.hasScripts()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Path scripts = skill.getDirectory().resolve(Skill.SCRIPTS_DIR);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(scripts)) {
            for (Path file : files) {
                String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(file)
                        && (lower.endsWith(".jsh") || lower.endsWith(".java"))) {
                    names.add(file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.debug("列出技能脚本失败: {}", scripts);
        }
        return names;
    }

    private static List<String> listReferenceFiles(Skill skill) {
        if (!skill.hasReferences()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Path references = skill.getDirectory().resolve(Skill.REFERENCES_DIR);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(references)) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && SkillFileRepository.isTextFile(file)
                        && PathGuard.isInside(references, file)) {
                    names.add(file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.debug("列出参考文档失败: {}", references);
        }
        return names;
    }

    private static String truncateReference(String text) {
        return text.length() > MAX_REFERENCE_CHARS
                ? text.substring(0, MAX_REFERENCE_CHARS) + "\n...(内容已截断)"
                : text;
    }

    private static boolean hasSensitiveName(Skill skill) {
        return SensitiveDataRedactor.containsLikelyCredential(skill.getName());
    }

    private static String redactCatalogValue(String value) {
        if (value == null) {
            return "";
        }
        return SensitiveDataRedactor.containsLikelyCredential(value) ? "[已隐藏]" : value;
    }

    private static String redactDescription(String description) {
        if (description == null) {
            return "";
        }
        return SensitiveDataRedactor.containsLikelyCredential(description)
                ? "[描述包含疑似凭据，已隐藏]" : description.strip();
    }
}
