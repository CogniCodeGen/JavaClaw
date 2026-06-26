package com.javaclaw.skill;

import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 技能管理器
 *
 * <p>负责技能的增删改查和持久化。每个技能以独立目录存储在 {@code skills/} 下，
 * 核心文件为 {@code SKILL.md}，使用 YAML Front Matter 存储元数据 + Markdown 正文存储提示词。</p>
 *
 * <p>标准目录结构：
 * <pre>
 * skills/
 * └── my-skill/
 *     ├── SKILL.md              ← [核心] 元数据 + 提示词
 *     ├── scripts/              ← [可选] 可执行脚本
 *     ├── references/           ← [可选] 参考文档
 *     └── assets/               ← [可选] 静态资源
 * </pre>
 *
 * <p>{@code SKILL.md} 格式：
 * <pre>
 * ---
 * name: 技能名称
 * description: 技能描述
 * enabled: true
 * ---
 *
 * 提示词正文...
 * </pre>
 *
 * @author JavaClaw
 */
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private static final SkillManager INSTANCE = new SkillManager();

    /** 技能包配置文件名（位于 skills/ 根目录） */
    private static final String BUNDLES_FILE = "bundles.json";

    private final Path skillsDir;
    private final List<Skill> skills;
    private final List<SkillBundle> bundles;

    /**
     * 动态注册技能（owner → 技能列表）—— 由插件等运行时来源经 {@link #registerDynamicSkills} 注册，
     * <b>不落盘</b>、仅存活于内存，并入渐进式暴露（L0 目录 / L1 详情）；来源卸载时经
     * {@link #unregisterDynamicSkills} 同步移除，不影响磁盘技能。
     */
    private final java.util.Map<String, List<Skill>> dynamicSkills = new java.util.concurrent.ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private SkillManager() {
        this.skillsDir = Path.of(System.getProperty("user.dir"), "skills");
        this.skills = new ArrayList<>();
        this.bundles = new ArrayList<>();
        initDirectory();
        loadAll();
        loadBundles();
    }

    public static SkillManager getInstance() {
        return INSTANCE;
    }

    /**
     * 初始化 skills 根目录
     */
    private void initDirectory() {
        try {
            Files.createDirectories(skillsDir);
            log.info("技能目录已初始化: {}", skillsDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("创建技能目录失败", e);
        }
    }

    // ==================== 加载 ====================

    /**
     * 扫描 skills 目录，加载所有包含 SKILL.md 的子目录
     */
    private void loadAll() {
        skills.clear();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, Files::isDirectory)) {
            for (Path dir : stream) {
                // 跳过隐藏目录（如 .history 等内部维护目录）
                if (dir.getFileName().toString().startsWith(".")) {
                    continue;
                }
                Path skillFile = dir.resolve(Skill.SKILL_FILE);
                if (!Files.exists(skillFile)) {
                    continue;
                }
                try {
                    Skill skill = parseSkillFile(dir);
                    skills.add(skill);
                } catch (IOException e) {
                    log.warn("加载技能失败: {}", dir.getFileName(), e);
                }
            }
            log.info("已加载 {} 个技能", skills.size());
        } catch (IOException e) {
            log.error("读取技能目录失败", e);
        }
    }

    /**
     * 使用 AgentScope MarkdownSkillParser 解析 SKILL.md（YAML Front Matter + Markdown 正文）
     */
    private Skill parseSkillFile(Path dir) throws IOException {
        Path skillFile = dir.resolve(Skill.SKILL_FILE);
        String raw = Files.readString(skillFile, StandardCharsets.UTF_8);

        Skill skill = new Skill();
        skill.setId(dir.getFileName().toString());
        skill.setDirectory(dir);

        MarkdownSkillParser.ParsedMarkdown parsed = MarkdownSkillParser.parse(raw);
        // 1.0.12 起 frontmatter 值类型升为 Object（支持非字符串 YAML 值），统一转字符串使用
        Map<String, Object> metadata = parsed.getMetadata();

        skill.setName(stringValue(metadata, "name", skill.getId()));
        skill.setDescription(stringValue(metadata, "description", ""));
        skill.setEnabled(Boolean.parseBoolean(stringValue(metadata, "enabled", "true")));
        skill.setContent(parsed.getContent());

        // 扩展元数据（全部向后兼容：旧 SKILL.md 缺字段时回落默认值）
        skill.setVersion(stringValue(metadata, "version", "1.0.0"));
        skill.setCategory(stringValue(metadata, "category", ""));
        skill.setTags(listValue(metadata, "tags"));
        skill.setSource(SkillSource.fromKey(stringValue(metadata, "source", "user")));
        skill.setUserModified(Boolean.parseBoolean(stringValue(metadata, "user-modified", "false")));
        skill.setPlatforms(listValue(metadata, "platforms"));
        skill.setRequiresToolGroups(listValue(metadata, "requires_toolsets"));
        skill.setFallbackForToolGroups(listValue(metadata, "fallback_for_toolsets"));

        return skill;
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
        if (ownerId == null || skills == null || skills.isEmpty()) {
            return;
        }
        dynamicSkills.put(ownerId, List.copyOf(skills));
        log.info("已注册动态技能 owner={}，{} 个：{}", ownerId, skills.size(),
                skills.stream().map(Skill::getName).toList());
    }

    /**
     * 移除某来源的全部动态技能（来源卸载时调用）。不影响磁盘技能。
     *
     * @param ownerId 来源标识
     */
    public void unregisterDynamicSkills(String ownerId) {
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
        String dirName = sanitizeDirName(name);
        if (dirName.isEmpty() || Files.exists(skillsDir.resolve(dirName))) {
            dirName = dirName + "-" + System.currentTimeMillis();
        }

        Path dir = skillsDir.resolve(dirName);
        Skill skill = new Skill(dirName, name, description, enabled);
        skill.setContent(content);
        skill.setDirectory(dir);
        skills.add(skill);
        saveSkill(skill);
        log.info("已创建技能: {} ({})", name, dirName);
        return skill;
    }

    /**
     * 更新技能并持久化 SKILL.md
     */
    public void updateSkill(Skill skill) {
        saveSkill(skill);
        log.info("已更新技能: {} ({})", skill.getName(), skill.getId());
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
        if (getSkillByName(name) != null) {
            return null;
        }
        Skill skill = createSkill(name, description, content, true);
        skill.setSource(SkillSource.AGENT);
        skill.setCategory(category);
        skill.setTags(tags);
        saveSkill(skill);
        return skill;
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
        archiveVersion(skill);
        skill.setContent(content.replace(oldString, newString != null ? newString : ""));
        skill.setVersion(bumpVersion(skill.getVersion(), BumpLevel.PATCH));
        saveSkill(skill);
        log.info("已修补技能: {} → v{}", name, skill.getVersion());
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
        archiveVersion(skill);
        skill.setContent(newContent);
        skill.setVersion(bumpVersion(skill.getVersion(), BumpLevel.MINOR));
        saveSkill(skill);
        log.info("已重写技能: {} → v{}", name, skill.getVersion());
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
        Path target = resolveInSkillDir(skill, relPath);
        if (target == null) {
            return "rel_path 非法：必须位于技能目录内，且不得指向 SKILL.md";
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content != null ? content : "", StandardCharsets.UTF_8);
            log.info("已写入技能支持文件: {}/{}", skill.getId(), relPath);
            return null;
        } catch (IOException e) {
            log.warn("写入技能支持文件失败: {}/{}", skill.getId(), relPath, e);
            return "写入失败：" + e.getMessage();
        }
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
        Path target = resolveInSkillDir(skill, relPath);
        if (target == null || !Files.isRegularFile(target)) {
            return "文件不存在或路径非法：" + relPath;
        }
        try {
            Files.delete(target);
            log.info("已删除技能支持文件: {}/{}", skill.getId(), relPath);
            return null;
        } catch (IOException e) {
            return "删除失败：" + e.getMessage();
        }
    }

    /**
     * 把 rel_path 解析到技能目录内，并做穿越防护；指向 SKILL.md 或越界时返回 null
     */
    private static Path resolveInSkillDir(Skill skill, String relPath) {
        if (skill.getDirectory() == null || relPath == null || relPath.isBlank()) {
            return null;
        }
        Path base = skill.getDirectory().toAbsolutePath().normalize();
        Path target = base.resolve(relPath.strip()).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            return null;
        }
        if (target.getFileName().toString().equals(Skill.SKILL_FILE) && base.equals(target.getParent())) {
            return null;
        }
        return target;
    }

    /**
     * 删除技能（使用 AgentScope SkillFileSystemHelper 递归删除整个目录）
     */
    public void deleteSkill(String id) {
        skills.removeIf(s -> s.getId().equals(id));
        Path dir = skillsDir.resolve(id);
        try {
            SkillFileSystemHelper.deleteDirectory(dir);
            log.info("已删除技能: {}", id);
        } catch (IOException e) {
            log.error("删除技能目录失败: {}", id, e);
        }
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
        if (skill == null || skill.getDirectory() == null) {
            return;
        }
        Path skillFile = skill.getDirectory().resolve(Skill.SKILL_FILE);
        if (!Files.exists(skillFile)) {
            return;
        }
        try {
            Path historyDir = skill.getDirectory().resolve(Skill.HISTORY_DIR);
            Files.createDirectories(historyDir);
            Path snapshot = historyDir.resolve("v" + skill.getVersion() + ".md");
            Files.copy(skillFile, snapshot, StandardCopyOption.REPLACE_EXISTING);
            log.info("已归档技能版本快照: {} v{}", skill.getId(), skill.getVersion());
        } catch (IOException e) {
            log.warn("归档技能版本失败: {} v{}", skill.getId(), skill.getVersion(), e);
        }
    }

    /**
     * 列出技能的历史版本号（按版本号降序，最新在前）
     */
    public List<String> listHistory(String id) {
        Skill skill = getSkill(id);
        if (skill == null || skill.getDirectory() == null) {
            return List.of();
        }
        Path historyDir = skill.getDirectory().resolve(Skill.HISTORY_DIR);
        if (!Files.isDirectory(historyDir)) {
            return List.of();
        }
        List<String> versions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(historyDir, "v*.md")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                versions.add(fileName.substring(1, fileName.length() - 3));
            }
        } catch (IOException e) {
            log.warn("读取技能历史版本失败: {}", id, e);
        }
        versions.sort(Comparator.comparing(SkillManager::versionSortKey).reversed());
        return versions;
    }

    /** 版本号排序键：各段补零对齐，保证 1.10.0 > 1.9.0 */
    private static String versionSortKey(String version) {
        StringBuilder sb = new StringBuilder();
        for (String part : version.split("\\.")) {
            sb.append(String.format("%06d", parseIntSafe(part))).append('.');
        }
        return sb.toString();
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 读取指定历史版本的 SKILL.md 全文（含 frontmatter），不存在时返回 null
     */
    public String readHistory(String id, String version) {
        Skill skill = getSkill(id);
        if (skill == null || skill.getDirectory() == null) {
            return null;
        }
        Path snapshot = skill.getDirectory().resolve(Skill.HISTORY_DIR).resolve("v" + version + ".md");
        if (!Files.exists(snapshot)) {
            return null;
        }
        try {
            return Files.readString(snapshot, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取技能历史版本失败: {} v{}", id, version, e);
            return null;
        }
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
        String snapshot = readHistory(id, version);
        if (skill == null || snapshot == null) {
            return false;
        }
        try {
            // 1. 归档当前版本
            archiveVersion(skill);
            // 2. 用历史快照覆盖 SKILL.md
            Path skillFile = skill.getDirectory().resolve(Skill.SKILL_FILE);
            Files.writeString(skillFile, snapshot, StandardCharsets.UTF_8);
            // 3. 重新解析并 bump 版本（回滚自身也是一次变更）
            Skill restored = parseSkillFile(skill.getDirectory());
            restored.setVersion(bumpVersion(version, BumpLevel.PATCH));
            saveSkill(restored);
            // 4. 刷新内存模型
            int idx = skills.indexOf(skill);
            if (idx >= 0) {
                skills.set(idx, restored);
            }
            log.info("已回滚技能 {} 到 v{}，新版本 v{}", id, version, restored.getVersion());
            return true;
        } catch (IOException e) {
            log.error("回滚技能失败: {} v{}", id, version, e);
            return false;
        }
    }

    // ==================== 持久化 ====================

    /**
     * 使用 AgentScope MarkdownSkillParser 生成并写入 SKILL.md
     */
    private void saveSkill(Skill skill) {
        Path dir = skillsDir.resolve(skill.getId());
        try {
            Files.createDirectories(dir);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("name", skill.getName());
            metadata.put("description", skill.getDescription() != null ? skill.getDescription() : "");
            metadata.put("enabled", String.valueOf(skill.isEnabled()));
            metadata.put("version", skill.getVersion());
            metadata.put("source", skill.getSource().getKey());
            metadata.put("user-modified", String.valueOf(skill.isUserModified()));
            // 可选字段仅在非空时写入，保持 SKILL.md 精简
            if (!skill.getCategory().isBlank()) {
                metadata.put("category", skill.getCategory());
            }
            if (!skill.getTags().isEmpty()) {
                metadata.put("tags", skill.getTags());
            }
            if (!skill.getPlatforms().isEmpty()) {
                metadata.put("platforms", skill.getPlatforms());
            }
            if (!skill.getRequiresToolGroups().isEmpty()) {
                metadata.put("requires_toolsets", skill.getRequiresToolGroups());
            }
            if (!skill.getFallbackForToolGroups().isEmpty()) {
                metadata.put("fallback_for_toolsets", skill.getFallbackForToolGroups());
            }

            String content = MarkdownSkillParser.generate(metadata,
                    skill.getContent() != null ? skill.getContent() : "");

            Path skillFile = dir.resolve(Skill.SKILL_FILE);
            Files.writeString(skillFile, content, StandardCharsets.UTF_8);

        } catch (IOException e) {
            log.error("保存技能失败: {}", skill.getId(), e);
        }
    }

    /** 从 frontmatter Map<String,Object> 中安全取字符串值 */
    private static String stringValue(Map<String, Object> metadata, String key, String defaultValue) {
        Object v = metadata.get(key);
        return v == null ? defaultValue : v.toString();
    }

    /**
     * 从 frontmatter 中安全取字符串列表：兼容 YAML 数组与逗号分隔字符串两种写法
     */
    private static List<String> listValue(Map<String, Object> metadata, String key) {
        Object v = metadata.get(key);
        List<String> result = new ArrayList<>();
        if (v == null) {
            return result;
        }
        if (v instanceof Collection<?> coll) {
            for (Object item : coll) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString().strip());
                }
            }
        } else {
            for (String part : v.toString().split(",")) {
                if (!part.isBlank()) {
                    result.add(part.strip());
                }
            }
        }
        return result;
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
        List<Skill> active = getActiveSkills(availableGroups);
        StringBuilder sb = new StringBuilder();
        if (!active.isEmpty()) {
            sb.append("\n\n## 可用技能目录\n");
            sb.append("以下是当前已配置的技能清单（仅名称与用途）。当任务与某技能相关时，请遵循对应技能的指令完成工作；\n");
            sb.append("若清单中列出某技能、但下方未提供其详细指令，且该技能与当前任务相关，\n");
            sb.append("请调用 skill_read 工具（参数 skill_name 填技能名称）按需拉取其完整指令后再执行；\n");
            sb.append("技能若列出参考文档，可再用 skill_read 的 path 参数单独拉取某个文档；\n");
            sb.append("技能若列出脚本，可用 jshell_run_script 工具（skill_name + script 文件名）运行其 Java 脚本。\n");
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
                    sb.append(desc.strip());
                }
                List<String> refs = listReferenceFiles(skill);
                if (!refs.isEmpty()) {
                    sb.append("；参考文档：").append(String.join("、", refs));
                }
                List<String> scripts = listScriptFiles(skill);
                if (!scripts.isEmpty()) {
                    sb.append("；脚本：").append(String.join("、", scripts))
                            .append("（可用 jshell_run_script 运行）");
                }
                sb.append("\n");
            }
        }

        // 技能包目录：让模型知道可成组加载
        if (com.javaclaw.config.AgentConfig.getInstance().isSkillBundlesEnabled()) {
            List<SkillBundle> enabledBundles = getEnabledBundles();
            if (!enabledBundles.isEmpty()) {
                sb.append("\n## 可用技能包\n");
                sb.append("技能包是一组配合使用的技能；任务匹配某包描述时，包内技能将成组注入。\n");
                for (SkillBundle bundle : enabledBundles) {
                    sb.append("- 【").append(bundle.name).append("】")
                            .append(bundle.description == null ? "" : bundle.description.strip())
                            .append("（含：").append(String.join("、", bundle.skills)).append("）\n");
                }
            }
        }

        // 经验沉淀 nudge（常驻轻量提示，借鉴 hermes-agent）
        if (com.javaclaw.config.AgentConfig.getInstance().isSkillNudgeEnabled()
                && !"off".equals(com.javaclaw.config.AgentConfig.getInstance().getSkillEvolutionMode())) {
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
                if (Files.isRegularFile(file) && isTextFile(file)) {
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
        List<Skill> enabled = getEnabledSkills();
        if (enabled.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n以下是用户配置的技能指令，请在回答时遵循：\n");
        for (Skill skill : enabled) {
            sb.append("\n【").append(skill.getName()).append("】\n");
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
                if (Files.isRegularFile(file) && isTextFile(file)) {
                    sb.append("--- ").append(file.getFileName()).append(" ---\n");
                    String text = Files.readString(file, StandardCharsets.UTF_8);
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
                .toList();
        if (filtered.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n以下是用户配置的技能指令，请在回答时遵循：\n");
        for (Skill skill : filtered) {
            sb.append("\n【").append(skill.getName()).append("】\n");
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
        // 穿越防护：必须落在 references/ 内
        if (!target.startsWith(refsDir) || !Files.isRegularFile(target) || !isTextFile(target)) {
            return null;
        }
        try {
            String text = Files.readString(target, StandardCharsets.UTF_8);
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
        loadBundles();
    }

    // ==================== 技能包（bundles） ====================

    /**
     * 加载 {@code skills/bundles.json} 技能包配置；文件不存在时为空列表
     */
    private void loadBundles() {
        bundles.clear();
        Path file = skillsDir.resolve(BUNDLES_FILE);
        if (!Files.exists(file)) {
            return;
        }
        try {
            SkillBundle[] loaded = mapper.readValue(file.toFile(), SkillBundle[].class);
            for (SkillBundle b : loaded) {
                if (b != null && b.name != null && !b.name.isBlank()) {
                    bundles.add(b);
                }
            }
            log.info("已加载 {} 个技能包", bundles.size());
        } catch (IOException e) {
            log.warn("加载技能包配置失败: {}", file, e);
        }
    }

    /** 全部技能包（含禁用，UI 管理用） */
    public List<SkillBundle> getBundles() {
        return new ArrayList<>(bundles);
    }

    /** 已启用的技能包 */
    public List<SkillBundle> getEnabledBundles() {
        return bundles.stream().filter(b -> b.enabled).toList();
    }

    /** 按名称查找已启用的技能包 */
    public SkillBundle getBundle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = name.strip();
        return bundles.stream()
                .filter(b -> b.enabled && target.equals(b.name))
                .findFirst()
                .orElse(null);
    }

    /** 覆盖保存技能包配置（UI 管理用） */
    public void saveBundles(List<SkillBundle> newBundles) {
        bundles.clear();
        if (newBundles != null) {
            bundles.addAll(newBundles);
        }
        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(skillsDir.resolve(BUNDLES_FILE).toFile(), bundles);
            log.info("已保存 {} 个技能包", bundles.size());
        } catch (IOException e) {
            log.error("保存技能包配置失败", e);
        }
    }

    /**
     * 构建技能包的注入提示词：包内全部技能正文（缺失跳过）+ 附加指令。
     * 包优先语义：调用方命中包时应注入本方法产物，而非逐技能拼接。
     *
     * @return 包不存在或包内无可用技能时返回空字符串
     */
    public String buildBundlePrompt(String bundleName) {
        SkillBundle bundle = getBundle(bundleName);
        if (bundle == null || bundle.skills.isEmpty()) {
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
            result.append("\n[本包附加指令]\n").append(bundle.extraInstructions.strip()).append("\n");
        }
        return result.toString();
    }

    /**
     * 将名称转换为合法的目录名
     */
    private String sanitizeDirName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.trim()
                .replaceAll("\\s+", "-")
                .replaceAll("[^\\p{L}\\p{N}\\-_]", "")
                .toLowerCase();
    }

    /**
     * 判断文件是否为文本文件（按扩展名）
     */
    private boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".yaml")
                || name.endsWith(".yml") || name.endsWith(".json") || name.endsWith(".xml")
                || name.endsWith(".html") || name.endsWith(".csv");
    }

}
