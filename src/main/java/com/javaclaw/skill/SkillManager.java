package com.javaclaw.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.util.SensitiveDataRedactor;
import com.javaclaw.util.PathGuard;
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

/**
 * 工作区技能协调器：维护内存索引、动态技能和模型提示词视图。
 *
 * <p>磁盘格式、路径约束和版本历史由 {@link SkillFileRepository} 负责；技能包 JSON
 * 由 {@link SkillBundleStore} 负责。实例随工作区 Spring Context 创建和销毁。</p>
 */
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final Path skillsDir;
    private final SkillFileRepository files;
    private final SkillBundleStore bundleStore;
    private final List<Skill> skills;

    /**
     * 动态注册技能（owner → 技能列表）—— 由插件等运行时来源经 {@link #registerDynamicSkills} 注册，
     * <b>不落盘</b>、仅存活于内存，并入渐进式暴露（L0 目录 / L1 详情）；来源卸载时经
     * {@link #unregisterDynamicSkills} 同步移除，不影响磁盘技能。
     */
    private final java.util.Map<String, List<Skill>> dynamicSkills = new java.util.concurrent.ConcurrentHashMap<>();
    private final AgentConfig settings;

    /**
     * 创建一个由工作区 Context 管理的技能仓库。
     *
     * <p>{@code skillsDir} 是宿主解析后的受管数据路径，不是模型提供的任意文件路径。
     * 实例不共享全局状态；切换工作区时由 Spring 整体替换。</p>
     */
    public SkillManager(Path skillsDir, ObjectMapper mapper, AgentConfig settings) {
        this.files = new SkillFileRepository(skillsDir);
        this.skillsDir = files.root();
        this.bundleStore = new SkillBundleStore(this.skillsDir, mapper);
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.skills = new ArrayList<>();
        loadAll();
    }

    // ==================== 加载 ====================

    /**
     * 扫描 skills 目录，加载所有包含 SKILL.md 的子目录
     */
    private void loadAll() {
        skills.clear();
        skills.addAll(files.loadAll());
    }

    // ==================== 查询 ====================

    /** 仅磁盘技能（用于管理 UI / 增删改；动态注册技能不在此列）。 */
    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills);
    }

    /** 已启用技能 = 磁盘启用技能 + 动态注册启用技能（后者并入渐进式暴露）。 */
    public List<Skill> getEnabledSkills() {
        List<Skill> result = new ArrayList<>();
        for (Skill s : skills) {
            if (s.isEnabled()) {
                result.add(s);
            }
        }
        for (List<Skill> ds : dynamicSkills.values()) {
            for (Skill s : ds) {
                if (s.isEnabled()) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    public Skill getSkill(String id) {
        return skills.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /** 按技能名称（YAML: name）查找技能：磁盘技能优先，未命中再查动态注册技能。 */
    public Skill getSkillByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = name.strip();
        Skill onDisk = skills.stream()
                .filter(s -> s.getName().equals(target))
                .findFirst()
                .orElse(null);
        if (onDisk != null) {
            return onDisk;
        }
        for (List<Skill> ds : dynamicSkills.values()) {
            for (Skill s : ds) {
                if (s.getName().equals(target)) {
                    return s;
                }
            }
        }
        return null;
    }

    // ==================== 动态技能注册（运行时来源，不落盘） ====================

    /**
     * 注册一组动态技能（如插件提供）—— 并入渐进式暴露，<b>不写磁盘</b>。同一 owner 重复注册会覆盖。
     *
     * @param ownerId 来源标识（如插件 id），用于卸载时整组移除
     * @param skills  技能列表（content 在内存持有，directory 为 null）
     */
    public void registerDynamicSkills(String ownerId, List<Skill> skills) {
        if (ownerId == null || ownerId.isBlank() || skills == null || skills.isEmpty()) {
            return;
        }
        List<Skill> safeSkills = skills.stream()
                .filter(java.util.Objects::nonNull)
                .filter(s -> !SensitiveDataRedactor.containsLikelyCredential(s.getName()))
                .filter(s -> !SensitiveDataRedactor.containsLikelyCredential(s.getDescription()))
                .filter(s -> !SensitiveDataRedactor.containsLikelyCredential(s.getContent()))
                .toList();
        if (safeSkills.isEmpty()) return;
        dynamicSkills.put(ownerId, List.copyOf(safeSkills));
        log.info("已注册动态技能 owner={}，{} 个", ownerId, safeSkills.size());
    }

    /**
     * 移除某来源的全部动态技能（来源卸载时调用）。不影响磁盘技能。
     *
     * @param ownerId 来源标识
     */
    public void unregisterDynamicSkills(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return;
        }
        List<Skill> removed = dynamicSkills.remove(ownerId);
        if (removed != null && !removed.isEmpty()) {
            log.info("已移除动态技能 owner={}，{} 个", ownerId, removed.size());
        }
    }

    /**
     * 用内存内容构建一个动态技能 Skill（directory=null，不落盘）。
     *
     * @param ownerId     来源标识（拼入 id 与标签）
     * @param name        技能名
     * @param description 描述
     * @param content     SKILL.md 正文
     * @return 可注册的动态 Skill
     */
    public Skill buildDynamicSkill(String ownerId, String name, String description, String content) {
        validateSkillDefinition(name, description, content);
        Skill s = new Skill("dyn-" + ownerId + "-" + name, name, description, true);
        s.setContent(content);
        s.setCategory("plugin");
        s.setTags(new ArrayList<>(List.of("plugin", ownerId)));
        return s;
    }

    /**
     * 获取在当前环境下激活的技能（启用 + 条件激活过滤）。
     *
     * <p>在 {@link #getEnabledSkills()} 基础上叠加 {@link Skill#isActiveFor} 判定：
     * platforms 按当前操作系统过滤；requires_toolsets / fallback_for_toolsets 按可用工具组过滤。</p>
     *
     * @param availableGroups 当前可用的工具组名集合，传 null 时跳过工具组判定（仅按 OS 过滤）
     */
    public List<Skill> getActiveSkills(Set<String> availableGroups) {
        String os = currentOs();
        return getEnabledSkills().stream()
                .filter(s -> s.isActiveFor(availableGroups, os))
                .toList();
    }

    /** 当前操作系统标识（windows/macos/linux），与 platforms 字段取值对齐 */
    private static String currentOs() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return "windows";
        }
        if (osName.contains("mac")) {
            return "macos";
        }
        return "linux";
    }

    // ==================== 增删改 ====================

    /**
     * 新建技能目录并生成 SKILL.md
     */
    public Skill createSkill(String name, String description, String content, boolean enabled) {
        validateSkillDefinition(name, description, content);
        String dirName = SkillFileRepository.sanitizeDirectoryName(name);
        if (dirName.isEmpty() || Files.exists(skillsDir.resolve(dirName))) {
            dirName = dirName + "-" + System.currentTimeMillis();
        }

        Path dir = skillsDir.resolve(dirName);
        Skill skill = new Skill(dirName, name, description, enabled);
        skill.setContent(content);
        skill.setDirectory(dir);
        Skill persisted = files.saveAndReadBack(skill, name);
        skills.add(persisted);
        log.info("已创建技能: {} ({})", name, dirName);
        return persisted;
    }

    /**
     * 更新技能并持久化 SKILL.md
     */
    public void updateSkill(Skill skill) {
        if (skill == null) {
            throw new IllegalArgumentException("技能不能为空");
        }
        validateSkillDefinition(skill.getName(), skill.getDescription(), skill.getContent());
        Skill persisted = files.saveAndReadBack(skill, skill.getName());
        replaceSkillSnapshot(skill.getId(), persisted);
        log.info("已更新技能: {} ({})", persisted.getName(), persisted.getId());
    }

    /**
     * 智能体创建技能（来源标记为 AGENT，初始版本 1.0.0）。
     *
     * <p>与 {@link #createSkill} 的区别：写入完整扩展元数据（source=agent / category / tags），
     * 供 skill_manage 工具与 SkillCurator 自学习闭环落盘使用。</p>
     *
     * @return 创建的技能；同名技能已存在时返回 null（应改用 patch/edit）
     */
    public Skill createAgentSkill(String name, String description, String content,
                                  String category, List<String> tags) {
        validateSkillDefinition(name, description, content);
        if (getSkillByName(name) != null) {
            return null;
        }
        String dirName = SkillFileRepository.sanitizeDirectoryName(name);
        if (dirName.isEmpty() || Files.exists(skillsDir.resolve(dirName))) {
            dirName = dirName + "-" + System.currentTimeMillis();
        }
        Path dir = skillsDir.resolve(dirName);
        Skill skill = new Skill(dirName, name, description, true);
        skill.setContent(content);
        skill.setDirectory(dir);
        skill.setSource(SkillSource.AGENT);
        skill.setCategory(category);
        skill.setTags(tags);
        Skill persisted = files.saveAndReadBack(skill, name);
        skills.add(persisted);
        return persisted;
    }

    /**
     * 对技能正文做定向修补（old → new 精确替换，Hermes patch 语义，token 高效）。
     *
     * <p>落盘前归档当前版本快照，成功后修订位 +1。</p>
     *
     * @return null 表示成功；否则返回失败原因（中文，可直接回给模型）
     */
    public String applyPatch(String name, String oldString, String newString) {
        Skill skill = getSkillByName(name);
        if (skill == null) {
            return "未找到名为「" + name + "」的技能";
        }
        String content = skill.getContent() != null ? skill.getContent() : "";
        if (oldString == null || oldString.isEmpty()) {
            return "old_string 为空，无法定位修补位置";
        }
        int first = content.indexOf(oldString);
        if (first < 0) {
            return "old_string 在技能「" + name + "」正文中未找到，请先用 skill_read 查看当前内容";
        }
        if (content.indexOf(oldString, first + 1) >= 0) {
            return "old_string 在技能「" + name + "」正文中出现多次，请提供更长的唯一片段";
        }
        String updated = content.replace(oldString, newString != null ? newString : "");
        if (SensitiveDataRedactor.containsLikelyCredential(updated)) {
            return "修补后的技能仍包含疑似凭据，已拒绝落盘；请一次性完成脱敏";
        }
        // 清理历史凭据时不再归档旧正文，避免把秘密复制进版本历史。
        if (!SensitiveDataRedactor.containsLikelyCredential(content)) archiveVersion(skill);
        Skill candidate = copySkill(skill);
        candidate.setContent(updated);
        candidate.setVersion(bumpVersion(skill.getVersion(), BumpLevel.PATCH));
        Skill persisted = files.saveAndReadBack(candidate, candidate.getName());
        replaceSkillSnapshot(skill.getId(), persisted);
        log.info("已修补技能: {} → v{}", name, persisted.getVersion());
        return null;
    }

    /**
     * 整篇重写技能正文（Hermes edit 语义，用于结构性变更）。
     *
     * <p>落盘前归档当前版本快照，成功后次版本位 +1。</p>
     *
     * @return null 表示成功；否则返回失败原因
     */
    public String applyEdit(String name, String newContent) {
        Skill skill = getSkillByName(name);
        if (skill == null) {
            return "未找到名为「" + name + "」的技能";
        }
        if (newContent == null || newContent.isBlank()) {
            return "new_content 为空，整篇重写必须提供完整正文";
        }
        if (SensitiveDataRedactor.containsLikelyCredential(newContent)) {
            return SensitiveDataRedactor.credentialStorageDeniedReason();
        }
        if (!SensitiveDataRedactor.containsLikelyCredential(skill.getContent())) archiveVersion(skill);
        Skill candidate = copySkill(skill);
        candidate.setContent(newContent);
        candidate.setVersion(bumpVersion(skill.getVersion(), BumpLevel.MINOR));
        Skill persisted = files.saveAndReadBack(candidate, candidate.getName());
        replaceSkillSnapshot(skill.getId(), persisted);
        log.info("已重写技能: {} → v{}", name, persisted.getVersion());
        return null;
    }

    /**
     * 向技能目录写入支持文件（references/ 参考文档、assets/ 模板等）。
     * 路径做穿越防护，不得指向 SKILL.md 本体。
     *
     * @return null 表示成功；否则返回失败原因
     */
    public String writeSupportFile(String name, String relPath, String content) {
        Skill skill = getSkillByName(name);
        if (skill == null) {
            return "未找到名为「" + name + "」的技能";
        }
        return files.writeSupportFile(skill, relPath, content);
    }

    /**
     * 删除技能目录中的支持文件（不可删除 SKILL.md 本体）。
     *
     * @return null 表示成功；否则返回失败原因
     */
    public String removeSupportFile(String name, String relPath) {
        Skill skill = getSkillByName(name);
        if (skill == null) {
            return "未找到名为「" + name + "」的技能";
        }
        return files.removeSupportFile(skill, relPath);
    }

    /**
     * 删除技能（使用 AgentScope SkillFileSystemHelper 递归删除整个目录）
     */
    public void deleteSkill(String id) {
        skills.removeIf(s -> s.getId().equals(id));
        files.delete(id);
    }

    // ==================== 版本管理 ====================

    /**
     * 版本递增级别：PATCH = 修订位 +1（定向修补）；MINOR = 次版本位 +1（整篇重写/重大变更）
     */
    public enum BumpLevel { PATCH, MINOR }

    /**
     * 语义版本递增。version 非法时重置为 1.0.0 再递增。
     */
    public static String bumpVersion(String version, BumpLevel level) {
        int major = 1;
        int minor = 0;
        int patch = 0;
        if (version != null) {
            String[] parts = version.strip().split("\\.");
            try {
                if (parts.length >= 1) major = Integer.parseInt(parts[0]);
                if (parts.length >= 2) minor = Integer.parseInt(parts[1]);
                if (parts.length >= 3) patch = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignore) {
                major = 1;
                minor = 0;
                patch = 0;
            }
        }
        if (level == BumpLevel.MINOR) {
            minor++;
            patch = 0;
        } else {
            patch++;
        }
        return major + "." + minor + "." + patch;
    }

    /**
     * 把技能当前 SKILL.md 归档为版本快照 {@code skills/{id}/.history/v{version}.md}。
     *
     * <p>应在任何写入变更（patch/edit/rollback）落盘前调用，保证旧版本可回滚。
     * 全文快照而非 diff：实现简单、回滚直接，技能正文 KB 级磁盘代价可忽略。</p>
     */
    public void archiveVersion(Skill skill) {
        files.archive(skill);
    }

    /**
     * 列出技能的历史版本号（按版本号降序，最新在前）
     */
    public List<String> listHistory(String id) {
        return files.listHistory(getSkill(id));
    }

    /**
     * 读取指定历史版本的 SKILL.md 全文（含 frontmatter），不存在时返回 null
     */
    public String readHistory(String id, String version) {
        return files.readHistory(getSkill(id), version);
    }

    /**
     * 回滚技能到指定历史版本。
     *
     * <p>回滚也是一次变更：先归档当前版本，再用历史快照覆盖 SKILL.md，
     * 最后把 version 在历史版本基础上 bump 一个修订位（避免与既有快照版本号冲突）。</p>
     *
     * @return 回滚是否成功
     */
    public boolean rollback(String id, String version) {
        Skill skill = getSkill(id);
        Skill restored = files.rollback(skill, version);
        if (restored == null) {
            return false;
        }
        replaceSkillSnapshot(id, restored);
        log.info("已回滚技能 {} 到 v{}，新版本 v{}", id, version, restored.getVersion());
        return true;
    }

    // ==================== 持久化 ====================

    private void replaceSkillSnapshot(String id, Skill persisted) {
        for (int i = 0; i < skills.size(); i++) {
            if (java.util.Objects.equals(id, skills.get(i).getId())) {
                skills.set(i, persisted);
                return;
            }
        }
        skills.add(persisted);
    }

    private static Skill copySkill(Skill source) {
        Skill copy = new Skill(source.getId(), source.getName(), source.getDescription(), source.isEnabled());
        copy.setContent(source.getContent());
        copy.setDirectory(source.getDirectory());
        copy.setVersion(source.getVersion());
        copy.setTags(new ArrayList<>(source.getTags()));
        copy.setCategory(source.getCategory());
        copy.setSource(source.getSource());
        copy.setUserModified(source.isUserModified());
        copy.setPlatforms(new ArrayList<>(source.getPlatforms()));
        copy.setRequiresToolGroups(new ArrayList<>(source.getRequiresToolGroups()));
        copy.setFallbackForToolGroups(new ArrayList<>(source.getFallbackForToolGroups()));
        return copy;
    }

    private static void requireCredentialFree(String content) {
        SkillFileRepository.requireCredentialFree(content);
    }

    private static void validateSkillDefinition(
            String name, String description, String content) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        requireCredentialFree(name);
        requireCredentialFree(description);
        requireCredentialFree(content);
    }

    // ==================== 系统提示词集成 ====================

    /**
     * 构建技能目录（L0 元数据层，始终常驻系统提示词）—— 无条件激活过滤的兼容入口。
     *
     * @see #buildSkillCatalogPrompt(Set)
     */
    public String buildSkillCatalogPrompt() {
        return buildSkillCatalogPrompt(null);
    }

    /**
     * 构建技能目录（L0 元数据层，始终常驻系统提示词）。
     *
     * <p>仅包含激活技能的「名称 + 分类/标签 + 描述 + references 文件清单」，不含正文，体量极小。
     * 这是渐进式暴露的最上层：让模型在任何路由场景下都知道有哪些技能存在，
     * 即使本轮路由未预载某技能的正文，也不会因路由漏判而让它对模型完全"消失"。
     * 三级加载：L0 本目录 → L1 {@code skill_read(skill_name)} 拉全文 →
     * L2 {@code skill_read(skill_name, path)} 拉单个参考文档。</p>
     *
     * <p>条件激活：按 {@link Skill#isActiveFor} 过滤（platforms 按当前 OS；
     * requires/fallback_for_toolsets 按本轮可用工具组）。
     * 末尾按配置追加「经验沉淀」nudge（借鉴 hermes-agent periodic nudges）。</p>
     *
     * @param availableGroups 本轮可用的工具组名集合，传 null 时跳过工具组判定
     * @return 技能目录提示词，无激活技能时返回空字符串
     */
    public String buildSkillCatalogPrompt(Set<String> availableGroups) {
        List<Skill> active = getActiveSkills(availableGroups).stream()
                .filter(skill -> !hasSensitiveName(skill))
                .toList();
        StringBuilder sb = new StringBuilder();
        if (!active.isEmpty()) {
            sb.append("\n\n## 可用技能目录\n");
            sb.append("以下是当前已配置的技能清单（仅名称与用途）。当任务与某技能相关时，请遵循对应技能的指令完成工作；\n");
            sb.append("若清单中列出某技能、但下方未提供其详细指令，且该技能与当前任务相关，\n");
            sb.append("请调用 skill_read 工具（参数 skill_name 填技能名称）按需拉取其完整指令后再执行；\n");
            sb.append("技能若列出参考文档，可再用 skill_read 的 path 参数单独拉取某个文档；\n");
            sb.append("严格项目隔离模式下不执行技能脚本；技能仅提供可审查的流程与参考资料。\n");
            for (Skill skill : active) {
                sb.append("- 【").append(skill.getName()).append("】");
                if (!skill.getCategory().isBlank()) {
                    sb.append("[").append(skill.getCategory()).append("] ");
                }
                if (!skill.getTags().isEmpty()) {
                    sb.append("(").append(String.join("/", skill.getTags())).append(") ");
                }
                String desc = skill.getDescription();
                if (desc != null && !desc.isBlank()) {
                    sb.append(SensitiveDataRedactor.containsLikelyCredential(desc)
                            ? "[描述包含疑似凭据，已隐藏]" : desc.strip());
                }
                List<String> refs = listReferenceFiles(skill);
                if (!refs.isEmpty()) {
                    sb.append("；参考文档：").append(String.join("、", refs));
                }
                List<String> scripts = listScriptFiles(skill);
                if (!scripts.isEmpty()) {
                    sb.append("；脚本：").append(String.join("、", scripts))
                            .append("（严格隔离下不可执行）");
                }
                sb.append("\n");
            }
        }

        // 技能包目录：让模型知道可成组加载
        if (settings.isSkillBundlesEnabled()) {
            List<SkillBundle> enabledBundles = getEnabledBundles().stream()
                    .filter(bundle -> !SensitiveDataRedactor.containsLikelyCredential(bundle.name))
                    .toList();
            if (!enabledBundles.isEmpty()) {
                sb.append("\n## 可用技能包\n");
                sb.append("技能包是一组配合使用的技能；任务匹配某包描述时，包内技能将成组注入。\n");
                for (SkillBundle bundle : enabledBundles) {
                    sb.append("- 【").append(bundle.name).append("】")
                            .append(bundle.description == null ? ""
                                    : SensitiveDataRedactor.containsLikelyCredential(bundle.description)
                                    ? "[描述包含疑似凭据，已隐藏]" : bundle.description.strip())
                            .append("（含：").append(bundle.skills.stream()
                                    .map(SkillManager::redactCatalogValue)
                                    .collect(java.util.stream.Collectors.joining("、")))
                            .append("）\n");
                }
            }
        }

        // 经验沉淀 nudge（常驻轻量提示，借鉴 hermes-agent）
        if (settings.isSkillNudgeEnabled()
                && !"off".equals(settings.getSkillEvolutionMode())) {
            sb.append("\n## 经验沉淀\n");
            sb.append("若本次完成了非平凡的多步骤工作流、踩坑后找到了可行路径、或被用户纠正了做法，\n");
            sb.append("请考虑调用 skill_create 把经验沉淀为新技能，或用 skill_patch 把新认知合入相关既有技能（小修优先 patch）。\n");
        }
        return sb.toString();
    }

    /** 列出技能 scripts/ 目录下可经 JShell 执行的 Java 脚本名（L0 目录展示用，.jsh/.java） */
    private List<String> listScriptFiles(Skill skill) {
        if (!skill.hasScripts()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Path scriptsDir = skill.getDirectory().resolve(Skill.SCRIPTS_DIR);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(scriptsDir)) {
            for (Path file : files) {
                String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(file) && (lower.endsWith(".jsh") || lower.endsWith(".java"))) {
                    names.add(file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.debug("列出技能脚本失败: {}", scriptsDir);
        }
        return names;
    }

    /** 列出技能 references/ 目录下的文本文件名（L0 目录展示用） */
    private List<String> listReferenceFiles(Skill skill) {
        if (!skill.hasReferences()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Path refsDir = skill.getDirectory().resolve(Skill.REFERENCES_DIR);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(refsDir)) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && SkillFileRepository.isTextFile(file)
                        && PathGuard.isInside(refsDir, file)) {
                    names.add(file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.debug("列出参考文档失败: {}", refsDir);
        }
        return names;
    }

    /**
     * 将所有已启用技能的提示词拼接为系统提示词补充
     *
     * <p>如果技能包含 references/ 目录，会自动附加参考文档内容。</p>
     *
     * @return 拼接后的技能提示词，无启用技能时返回空字符串
     */
    public String buildEnabledSkillsPrompt() {
        List<Skill> enabled = getEnabledSkills().stream()
                .filter(skill -> !hasSensitiveName(skill))
                .toList();
        if (enabled.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n以下是用户配置的技能指令，请在回答时遵循：\n");
        for (Skill skill : enabled) {
            sb.append("\n【").append(skill.getName()).append("】\n");
            if (SensitiveDataRedactor.containsLikelyCredential(skill.getContent())) {
                sb.append("[技能正文包含疑似凭据，系统已阻止载入]\n");
                continue;
            }
            sb.append(skill.getContent()).append("\n");

            // 附加 references/ 下的文档内容
            if (skill.hasReferences()) {
                String refs = loadReferences(skill);
                if (!refs.isEmpty()) {
                    sb.append("\n[参考文档]\n").append(refs).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 读取技能 references/ 目录下的所有文本文件内容
     */
    private String loadReferences(Skill skill) {
        Path refsDir = skill.getDirectory().resolve(Skill.REFERENCES_DIR);
        StringBuilder sb = new StringBuilder();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(refsDir)) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && SkillFileRepository.isTextFile(file)
                        && PathGuard.isInside(refsDir, file)) {
                    sb.append("--- ").append(file.getFileName()).append(" ---\n");
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    if (SensitiveDataRedactor.containsLikelyCredential(text)) {
                        sb.append("[该参考文档包含疑似凭据，系统已阻止载入]\n\n");
                        continue;
                    }
                    // 限制单个参考文档最大 10000 字符
                    if (text.length() > 10000) {
                        text = text.substring(0, 10000) + "\n...(内容已截断)";
                    }
                    sb.append(text).append("\n\n");
                }
            }
        } catch (IOException e) {
            log.warn("读取参考文档失败: {}", refsDir, e);
        }
        return sb.toString();
    }

    /**
     * 构建指定技能的提示词（路由筛选后使用）
     *
     * <p>只拼接 skillNames 中指定的已启用技能，减少无关技能对模型的干扰。</p>
     *
     * @param skillNames 需要注入的技能名列表，为空时返回空字符串
     * @return 筛选后的技能提示词
     */
    public String buildFilteredSkillsPrompt(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) {
            return "";
        }
        List<Skill> filtered = getEnabledSkills().stream()
                .filter(s -> skillNames.contains(s.getName()))
                .filter(skill -> !hasSensitiveName(skill))
                .toList();
        if (filtered.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n以下是用户配置的技能指令，请在回答时遵循：\n");
        for (Skill skill : filtered) {
            sb.append("\n【").append(skill.getName()).append("】\n");
            if (SensitiveDataRedactor.containsLikelyCredential(skill.getContent())) {
                sb.append("[技能正文包含疑似凭据，系统已阻止载入]\n");
                continue;
            }
            sb.append(skill.getContent()).append("\n");
            if (skill.hasReferences()) {
                String refs = loadReferences(skill);
                if (!refs.isEmpty()) {
                    sb.append("\n[参考文档]\n").append(refs).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 构建单个技能的详细指令（L2 正文 + references），供 {@code skill_read} 工具按需拉取。
     *
     * <p>渐进式暴露的 L2 拉取入口：L1 目录（{@link #buildSkillCatalogPrompt()}）只告知技能存在，
     * 模型判断相关后调用本方法获取完整内容，避免一次性把所有技能正文塞进上下文。</p>
     *
     * @param name 技能名称（须与 L1 目录中展示的名称一致）
     * @return 该技能的正文 + 参考文档；技能不存在或未启用时返回 {@code null}
     */
    public String buildSkillDetail(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = name.strip();
        Skill skill = getEnabledSkills().stream()
                .filter(s -> s.getName().equals(target))
                .findFirst()
                .orElse(null);
        if (skill == null) {
            return null;
        }
        if (hasSensitiveName(skill)) {
            return null;
        }
        if (SensitiveDataRedactor.containsLikelyCredential(skill.getContent())) {
            return "【" + skill.getName() + "】\n[技能正文包含疑似凭据，系统已阻止载入]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(skill.getName()).append("】\n");
        sb.append(skill.getContent() != null ? skill.getContent() : "").append("\n");
        if (skill.hasReferences()) {
            String refs = loadReferences(skill);
            if (!refs.isEmpty()) {
                sb.append("\n[参考文档]\n").append(refs).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 读取技能的单个参考文档（L2 文件级按需拉取，供 {@code skill_read} 的 path 参数使用）。
     *
     * <p>相比整包注入 references，按文件拉取进一步省 token：L0 目录列出文件名，
     * 模型判断相关后只取需要的那一份。</p>
     *
     * @param name    技能名称
     * @param relPath references/ 下的文件名（如 api-doc.md），也允许带 references/ 前缀
     * @return 文档内容（超长截断）；技能/文件不存在或非文本文件时返回 {@code null}
     */
    public String buildReferenceDetail(String name, String relPath) {
        Skill skill = getSkillByName(name);
        if (skill == null || !skill.isEnabled() || skill.getDirectory() == null
                || relPath == null || relPath.isBlank()) {
            return null;
        }
        String cleaned = relPath.strip().replace('\\', '/');
        if (cleaned.startsWith(Skill.REFERENCES_DIR + "/")) {
            cleaned = cleaned.substring(Skill.REFERENCES_DIR.length() + 1);
        }
        Path refsDir = skill.getDirectory().resolve(Skill.REFERENCES_DIR).toAbsolutePath().normalize();
        Path target = refsDir.resolve(cleaned).normalize();
        // 穿越防护：必须落在 references/ 内（含符号链接真实位置校验）
        if (!target.startsWith(refsDir) || !Files.isRegularFile(target)
                || !SkillFileRepository.isTextFile(target)
                || !com.javaclaw.util.PathGuard.isInside(refsDir, target)) {
            return null;
        }
        try {
            String text = Files.readString(target, StandardCharsets.UTF_8);
            if (SensitiveDataRedactor.containsLikelyCredential(text)) {
                return "--- " + target.getFileName() + " ---\n[该参考文档包含疑似凭据，系统已阻止载入]";
            }
            if (text.length() > 10000) {
                text = text.substring(0, 10000) + "\n...(内容已截断)";
            }
            return "--- " + target.getFileName() + " ---\n" + text;
        } catch (IOException e) {
            log.warn("读取参考文档失败: {}", target, e);
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    public Path getSkillsDir() {
        return skillsDir;
    }

    public void reload() {
        loadAll();
        bundleStore.reload();
    }

    // ==================== 技能包（bundles） ====================

    /** 全部技能包（含禁用，UI 管理用） */
    public List<SkillBundle> getBundles() {
        return bundleStore.all();
    }

    /** 已启用的技能包 */
    public List<SkillBundle> getEnabledBundles() {
        return bundleStore.enabled();
    }

    /** 按名称查找已启用的技能包 */
    public SkillBundle getBundle(String name) {
        return bundleStore.enabled(name);
    }

    /** 覆盖保存技能包配置（UI 管理用） */
    public void saveBundles(List<SkillBundle> newBundles) {
        bundleStore.save(newBundles);
    }

    /**
     * 构建技能包的注入提示词：包内全部技能正文（缺失跳过）+ 附加指令。
     * 包优先语义：调用方命中包时应注入本方法产物，而非逐技能拼接。
     *
     * @return 包不存在或包内无可用技能时返回空字符串
     */
    public String buildBundlePrompt(String bundleName) {
        SkillBundle bundle = getBundle(bundleName);
        if (bundle == null || bundle.skills.isEmpty()
                || SensitiveDataRedactor.containsLikelyCredential(bundle.name)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int loaded = 0;
        for (String skillName : bundle.skills) {
            String detail = buildSkillDetail(skillName);
            if (detail == null) {
                log.warn("技能包「{}」内技能「{}」不存在或未启用，已跳过", bundle.name, skillName);
                continue;
            }
            sb.append("\n").append(detail);
            loaded++;
        }
        if (loaded == 0) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        result.append("\n\n## 技能包【").append(bundle.name).append("】\n");
        result.append("以下 ").append(loaded).append(" 项技能作为一组配合使用：\n");
        result.append(sb);
        if (bundle.extraInstructions != null && !bundle.extraInstructions.isBlank()) {
            result.append("\n[本包附加指令]\n")
                    .append(SensitiveDataRedactor.containsLikelyCredential(bundle.extraInstructions)
                            ? "[附加指令包含疑似凭据，已隐藏]"
                            : bundle.extraInstructions.strip())
                    .append("\n");
        }
        return result.toString();
    }

    private static boolean hasSensitiveName(Skill skill) {
        return SensitiveDataRedactor.containsLikelyCredential(skill.getName());
    }

    private static String redactCatalogValue(String value) {
        if (value == null) return "";
        return SensitiveDataRedactor.containsLikelyCredential(value) ? "[已隐藏]" : value;
    }

}
