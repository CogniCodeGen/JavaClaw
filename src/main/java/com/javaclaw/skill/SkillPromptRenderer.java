package com.javaclaw.skill;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.util.PathGuard;
import com.javaclaw.util.SensitiveDataRedactor;
import com.javaclaw.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private static final int MAX_CATALOG_TOKENS = 1_200;

    interface Source {
        List<Skill> getEnabledSkills();

        List<Skill> getActiveSkills(Set<String> availableGroups);

        Skill getSkillByName(String name);

        List<SkillBundle> getEnabledBundles();

        SkillBundle getBundle(String name);
    }

    private final Source source;
    private final SkillCatalogSnapshotFactory snapshots;

    SkillPromptRenderer(Source source, AgentConfig settings) {
        this.source = java.util.Objects.requireNonNull(source, "source");
        this.snapshots = new SkillCatalogSnapshotFactory(
                source, java.util.Objects.requireNonNull(settings, "settings"));
    }

    String buildCatalog(Set<String> availableGroups) {
        return buildCatalog(snapshot(availableGroups));
    }

    String buildCatalog(CatalogSnapshot snapshot) {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.skills().isEmpty()) return "";
        StringBuilder prompt = new StringBuilder();
        appendSkillCatalog(prompt, snapshot.skills());
        appendBundleCatalog(prompt, snapshot.bundles());
        String catalog = prompt.toString();
        if (TokenEstimator.estimate(catalog) <= MAX_CATALOG_TOKENS) {
            return catalog;
        }
        String compressed = buildCompressedCatalog(snapshot);
        if (TokenEstimator.estimate(compressed) <= MAX_CATALOG_TOKENS) {
            return compressed;
        }
        SkillCatalogPage first = readCatalogPage(snapshot, 0, MAX_CATALOG_TOKENS);
        return first == null ? "" : first.content();
    }

    CatalogSnapshot snapshot(Set<String> availableGroups) {
        return snapshot(availableGroups, false);
    }

    CatalogSnapshot readableSnapshot(Set<String> availableGroups) {
        return snapshot(availableGroups, true);
    }

    private CatalogSnapshot snapshot(Set<String> availableGroups, boolean captureReadableContent) {
        return snapshots.capture(availableGroups, captureReadableContent);
    }

    private void appendSkillCatalog(StringBuilder prompt, List<CatalogSkill> active) {
        if (active.isEmpty()) {
            return;
        }
        prompt.append("\n\n## 可用技能（L0）\n")
                .append("仅列元数据。相关时先调用 skill_read(skill_name) 读取正文，再按需用 path 读取单份参考资料。\n");
        for (CatalogSkill skill : active) {
            appendCatalogEntry(prompt, skill);
        }
    }

    private void appendCatalogEntry(StringBuilder prompt, CatalogSkill skill) {
        prompt.append("- ").append(catalogReference(skill));
        if (!skill.category().isBlank()) {
            prompt.append("[").append(skill.category()).append("] ");
        }
        if (!skill.description().isBlank()) {
            if (skill.category().isBlank()) prompt.append(" ");
            prompt.append(skill.description());
        }
        prompt.append("\n");
    }

    private void appendBundleCatalog(StringBuilder prompt, List<CatalogBundle> bundles) {
        if (bundles.isEmpty()) {
            return;
        }
        prompt.append("\n## 可用技能包（仅索引）\n")
                .append("技能包不会自动注入正文；按需分别调用 skill_read。\n");
        for (CatalogBundle bundle : bundles) {
            prompt.append("- 【").append(bundle.name()).append("】")
                    .append(bundle.description())
                    .append("（含：").append(String.join("、", bundle.members()))
                    .append("）\n");
        }
    }

    private String buildCompressedCatalog(CatalogSnapshot snapshot) {
        StringBuilder compact = new StringBuilder("\n\n## 可用技能（L0 压缩索引）\n")
                .append("需要时调用 skill_read；名称：");
        compact.append(snapshot.skills().stream()
                .map(SkillPromptRenderer::catalogReference)
                .collect(Collectors.joining("、")));
        if (!snapshot.bundles().isEmpty()) {
            compact.append("\n技能包：");
            for (int i = 0; i < snapshot.bundles().size(); i++) {
                if (i > 0) compact.append("；");
                CatalogBundle bundle = snapshot.bundles().get(i);
                compact.append(bundle.name()).append("(")
                        .append(String.join("、", bundle.members()))
                        .append(")");
            }
        }
        compact.append("\n");
        return compact.toString();
    }

    SkillCatalogPage readCatalogPage(CatalogSnapshot snapshot, int cursor, int maxTokens) {
        if (snapshot == null || cursor < 0 || maxTokens <= 0) return null;
        List<String> entries = catalogPageEntries(snapshot);
        if (cursor > entries.size()) return null;
        String header = "## 可用技能（L0 分页索引）\n"
                + "读取正文请用 skill_read(skill_name)；继续目录请用 skill_read(\"*\", cursor)。\n";
        StringBuilder body = new StringBuilder();
        int next = cursor;
        while (next < entries.size()) {
            String line = entries.get(next) + "\n";
            String footer = catalogCursorFooter(next + 1, next + 1 < entries.size());
            if (TokenEstimator.estimate(header + body + line + footer) > maxTokens) break;
            body.append(line);
            next++;
        }
        if (next == cursor && next < entries.size()) {
            String footer = catalogCursorFooter(next + 1, next + 1 < entries.size());
            int remaining = maxTokens - TokenEstimator.estimate(header + footer);
            if (remaining <= 0) return null;
            body.append(TokenEstimator.truncateToTokens(entries.get(next), remaining)).append("\n");
            next++;
        }
        boolean more = next < entries.size();
        String content = header + body + catalogCursorFooter(next, more);
        if (TokenEstimator.estimate(content) > maxTokens) {
            throw new IllegalStateException("skill catalog page exceeded token budget");
        }
        return new SkillCatalogPage(content, next, more);
    }

    private static List<String> catalogPageEntries(CatalogSnapshot snapshot) {
        List<String> entries = new ArrayList<>();
        for (CatalogSkill skill : snapshot.skills()) {
            entries.add("- 技能 " + catalogReference(skill));
        }
        for (CatalogBundle bundle : snapshot.bundles()) {
            entries.add("- 技能包【" + bundle.name() + "】");
            bundle.members().forEach(member -> entries.add("  - 成员 " + member));
        }
        return List.copyOf(entries);
    }

    private static String catalogCursorFooter(int nextCursor, boolean hasMore) {
        return hasMore
                ? "[next_cursor=" + nextCursor
                + "；继续调用 skill_read(\"*\", cursor=" + nextCursor + ")]"
                : "[next_cursor=END]";
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

    SkillContentPage readProgressivePage(String name, String relativePath,
                                         int cursor, int maxCharacters) {
        return readProgressivePage(name, relativePath, cursor, maxCharacters, null);
    }

    SkillContentPage readProgressivePage(
            String name, String relativePath, int cursor, int maxCharacters,
            Set<String> allowedSkillNames) {
        if (name == null || name.isBlank() || cursor < 0 || maxCharacters <= 0) {
            return null;
        }
        String targetName = name.strip();
        if (allowedSkillNames != null && !allowedSkillNames.contains(targetName)) return null;
        Skill skill = source.getEnabledSkills().stream()
                .filter(candidate -> candidate.getName().equals(targetName))
                .findFirst()
                .orElse(null);
        if (skill == null || hasSensitiveName(skill)) {
            return null;
        }
        return readProgressivePage(
                snapshots.captureSkill(skill, true), relativePath, cursor, maxCharacters);
    }

    SkillContentPage readProgressivePage(
            CatalogSnapshot snapshot, String lookup, String relativePath,
            int cursor, int maxCharacters) {
        return readProgressivePage(
                snapshot, lookup, relativePath, cursor, null, maxCharacters);
    }

    SkillContentPage readProgressivePage(
            CatalogSnapshot snapshot, String lookup, String relativePath,
            int cursor, Integer line, int maxCharacters) {
        return readProgressivePage(
                snapshot, lookup, relativePath, cursor, line, maxCharacters, null);
    }

    SkillContentPage readProgressivePage(
            CatalogSnapshot snapshot, String lookup, String relativePath,
            int cursor, Integer line, int maxCharacters,
            SkillReferenceReadSession referenceReads) {
        if (snapshot == null || lookup == null || lookup.isBlank()) return null;
        CatalogSkill skill = snapshot.resolveSkill(lookup);
        return skill == null ? null
                : readProgressivePage(
                        skill, relativePath, cursor, line, maxCharacters, referenceReads);
    }

    SkillReferenceSearchResult searchReferences(
            CatalogSnapshot snapshot, String lookup, String relativePath,
            String query, int maxCharacters) {
        return searchReferences(
                snapshot, lookup, relativePath, query, maxCharacters, null);
    }

    SkillReferenceSearchResult searchReferences(
            CatalogSnapshot snapshot, String lookup, String relativePath,
            String query, int maxCharacters, SkillReferenceReadSession referenceReads) {
        if (snapshot == null || lookup == null || lookup.isBlank()) return null;
        CatalogSkill skill = snapshot.resolveSkill(lookup);
        return skill == null ? null : SkillReferenceAccess.search(
                skill, relativePath, query, maxCharacters, referenceReads);
    }

    private static SkillContentPage readProgressivePage(
            CatalogSkill skill, String relativePath, int cursor, int maxCharacters) {
        return readProgressivePage(skill, relativePath, cursor, null, maxCharacters);
    }

    private static SkillContentPage readProgressivePage(
            CatalogSkill skill, String relativePath, int cursor,
            Integer line, int maxCharacters) {
        return readProgressivePage(skill, relativePath, cursor, line, maxCharacters, null);
    }

    private static SkillContentPage readProgressivePage(
            CatalogSkill skill, String relativePath, int cursor,
            Integer line, int maxCharacters, SkillReferenceReadSession referenceReads) {
        if (cursor < 0 || maxCharacters <= 0) return null;
        if (relativePath == null || relativePath.isBlank()) {
            String body = skill.content();
            if (SensitiveDataRedactor.containsLikelyCredential(body)) {
                body = "[技能正文包含疑似凭据，系统已阻止载入]";
            }
            String full = "【" + skill.name() + " / SKILL.md】\n" + body
                    + (skill.referenceFiles().isEmpty() ? ""
                    : "\n\n[可按 path 单独读取的参考文件]\n- "
                    + String.join("\n- ", skill.referenceFiles()));
            if (cursor > full.length() || splitsSurrogatePair(full, cursor)) return null;
            int end = Math.min(full.length(), cursor + maxCharacters);
            if (splitsSurrogatePair(full, end)) {
                if (end - 1 > cursor) end--;
                else end++;
            }
            return new SkillContentPage(full.substring(cursor, end), end, end < full.length());
        }
        return SkillReferenceAccess.readPage(
                skill, relativePath, cursor, line, maxCharacters, referenceReads);
    }

    static String normalizeReferenceName(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        String cleaned = relativePath.strip().replace('\\', '/');
        if (cleaned.startsWith(Skill.REFERENCES_DIR + "/")) {
            cleaned = cleaned.substring(Skill.REFERENCES_DIR.length() + 1);
        }
        if (cleaned.isBlank() || cleaned.contains("/")
                || SensitiveDataRedactor.containsLikelyCredential(cleaned)) return null;
        return cleaned;
    }

    private static boolean splitsSurrogatePair(String value, int index) {
        return index > 0 && index < value.length()
                && Character.isHighSurrogate(value.charAt(index - 1))
                && Character.isLowSurrogate(value.charAt(index));
    }

    static Path resolveReference(Path references, String relativePath) {
        String cleaned = relativePath.strip().replace('\\', '/');
        if (cleaned.startsWith(Skill.REFERENCES_DIR + "/")) {
            cleaned = cleaned.substring(Skill.REFERENCES_DIR.length() + 1);
        }
        if (SensitiveDataRedactor.containsLikelyCredential(cleaned)) return null;
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

    private static String truncateReference(String text) {
        return text.length() > MAX_REFERENCE_CHARS
                ? text.substring(0, MAX_REFERENCE_CHARS) + "\n...(内容已截断)"
                : text;
    }

    private static boolean hasSensitiveName(Skill skill) {
        return SensitiveDataRedactor.containsLikelyCredential(skill.getName());
    }

    static String catalogReference(CatalogSkill skill) {
        String reference = "【" + skill.displayName() + "】";
        return skill.lookupKey().equals(skill.name())
                ? reference : reference + "（读取键：" + skill.lookupKey() + "）";
    }

    record CatalogSkill(
            String name,
            String lookupKey,
            String displayName,
            String category,
            String description,
            String content,
            Path directory,
            List<CatalogReference> references) {
        CatalogSkill {
            content = content == null ? "" : content;
            directory = directory == null ? null : directory.toAbsolutePath().normalize();
            references = List.copyOf(references == null ? List.of() : references);
        }

        List<String> referenceFiles() {
            return references.stream().map(CatalogReference::name).toList();
        }

        CatalogReference reference(String name) {
            return references.stream()
                    .filter(reference -> reference.name().equals(name))
                    .findFirst().orElse(null);
        }
    }

    record CatalogReference(String name, long capturedSize) {
        CatalogReference {
            name = java.util.Objects.requireNonNull(name, "name");
            capturedSize = Math.max(0, capturedSize);
        }
    }

    record CatalogBundle(String name, String description, List<String> members) {
        CatalogBundle {
            members = List.copyOf(members == null ? List.of() : members);
        }
    }

    record CatalogSnapshot(List<CatalogSkill> skills, List<CatalogBundle> bundles) {
        CatalogSnapshot {
            skills = List.copyOf(skills == null ? List.of() : skills);
            bundles = List.copyOf(bundles == null ? List.of() : bundles);
        }

        String resolveLookup(String lookup) {
            CatalogSkill skill = resolveSkill(lookup);
            return skill == null ? null : skill.name();
        }

        CatalogSkill resolveSkill(String lookup) {
            if (lookup == null || lookup.isBlank() || lookup.strip().equals("*")) return null;
            String target = lookup.strip();
            return skills.stream()
                    .filter(skill -> skill.lookupKey().equals(target) || skill.name().equals(target))
                    .findFirst().orElse(null);
        }

        Set<String> activeNames() {
            return skills.stream().map(CatalogSkill::name)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
