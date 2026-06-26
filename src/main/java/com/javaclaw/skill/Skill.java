package com.javaclaw.skill;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 技能数据模型
 *
 * <p>每个技能对应 {@code skills/} 目录下的一个子目录，标准结构如下：
 * <pre>
 * my-skill/                 # 技能根目录（文件夹名即为技能 ID）
 * ├── SKILL.md              # [核心] 必须存在。包含 YAML 元数据和提示词指令
 * ├── scripts/              # [可选] 存放可执行脚本 (Python, Shell, Jar 等)
 * │   └── process.py
 * ├── references/           # [可选] 存放参考文档、API 手册、知识库
 * │   └── api-doc.md
 * ├── assets/               # [可选] 存放静态资源 (模板, 配置文件, 图片)
 * │   └── config.yaml
 * └── .history/             # [自动] 版本历史快照（v{version}.md，由 SkillManager 维护）
 * </pre>
 *
 * <p>{@code SKILL.md} 使用 YAML Front Matter 格式（缺失字段均有向后兼容默认值）：
 * <pre>
 * ---
 * name: 技能名称
 * description: 技能描述
 * enabled: true
 * version: 1.0.0
 * category: 分类
 * tags: [标签1, 标签2]
 * source: user            # builtin | user | agent
 * user-modified: false    # 用户修改保护位，置位后 agent 不得静默覆盖
 * platforms: [macos]      # 限定操作系统，空为不限
 * requires_toolsets: []   # 仅在指定工具组全部可用时激活
 * fallback_for_toolsets: []  # 仅在指定工具组不可用时激活（备选方案语义）
 * ---
 *
 * 提示词正文内容...
 * </pre>
 *
 * @author JavaClaw
 */
public class Skill {

    /** SKILL.md 文件名 */
    public static final String SKILL_FILE = "SKILL.md";

    /** 可选子目录名 */
    public static final String SCRIPTS_DIR = "scripts";
    public static final String REFERENCES_DIR = "references";
    public static final String ASSETS_DIR = "assets";

    /** 版本历史快照目录名（由 SkillManager 自动维护，loadAll 时跳过） */
    public static final String HISTORY_DIR = ".history";

    /** 技能唯一标识（即目录名） */
    private String id;

    /** 技能名称（YAML: name） */
    private String name;

    /** 技能描述（YAML: description） */
    private String description;

    /** 是否启用（YAML: enabled） */
    private boolean enabled;

    /** 提示词正文内容（YAML Front Matter 之后的 Markdown 部分） */
    private String content;

    /** 技能根目录路径 */
    private Path directory;

    /** 语义版本号（YAML: version，缺省补 1.0.0；patch 递增修订位，edit/create 递增次版本位） */
    private String version = "1.0.0";

    /** 标签列表（YAML: tags） */
    private List<String> tags = new ArrayList<>();

    /** 分类（YAML: category） */
    private String category = "";

    /** 技能来源（YAML: source，缺省 USER） */
    private SkillSource source = SkillSource.USER;

    /** 用户修改保护位（YAML: user-modified）：置位后 agent 的 auto 模式覆盖强制降级为提案 */
    private boolean userModified = false;

    /** 限定操作系统（YAML: platforms，取值 windows/macos/linux，空为不限） */
    private List<String> platforms = new ArrayList<>();

    /** 条件激活：仅在指定工具组全部可用时显示（YAML: requires_toolsets，空为无条件） */
    private List<String> requiresToolGroups = new ArrayList<>();

    /** 条件激活：仅在指定工具组不可用时显示，作为备选方案（YAML: fallback_for_toolsets，空为无条件） */
    private List<String> fallbackForToolGroups = new ArrayList<>();

    public Skill() {
        this.content = "";
    }

    public Skill(String id, String name, String description, boolean enabled) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.enabled = enabled;
        this.content = "";
    }

    // ==================== 条件激活判定 ====================

    /**
     * 判断技能在当前环境下是否激活（条件激活，Hermes requires/fallback_for_toolsets 语义）。
     *
     * <p>三个条件全部满足才激活（各条件的空列表视为无条件通过）：
     * <ol>
     *   <li>platforms 为空，或包含当前操作系统</li>
     *   <li>requiresToolGroups 为空，或所列工具组全部在 availableGroups 中</li>
     *   <li>fallbackForToolGroups 为空，或所列工具组全部不在 availableGroups 中（备选方案：正主可用时隐藏）</li>
     * </ol>
     *
     * @param availableGroups 当前可用的工具组名集合，传 null 时跳过工具组相关判定
     * @param os              当前操作系统标识（windows/macos/linux）
     */
    public boolean isActiveFor(Set<String> availableGroups, String os) {
        if (!platforms.isEmpty() && os != null && platforms.stream().noneMatch(p -> p.equalsIgnoreCase(os))) {
            return false;
        }
        if (availableGroups != null) {
            if (!requiresToolGroups.isEmpty() && !availableGroups.containsAll(requiresToolGroups)) {
                return false;
            }
            if (!fallbackForToolGroups.isEmpty()
                    && fallbackForToolGroups.stream().anyMatch(availableGroups::contains)) {
                return false;
            }
        }
        return true;
    }

    // ==================== 可选目录便捷方法 ====================

    /** scripts/ 目录是否存在 */
    public boolean hasScripts() {
        return directory != null && directory.resolve(SCRIPTS_DIR).toFile().isDirectory();
    }

    /** references/ 目录是否存在 */
    public boolean hasReferences() {
        return directory != null && directory.resolve(REFERENCES_DIR).toFile().isDirectory();
    }

    /** assets/ 目录是否存在 */
    public boolean hasAssets() {
        return directory != null && directory.resolve(ASSETS_DIR).toFile().isDirectory();
    }

    // ==================== Getter / Setter ====================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Path getDirectory() {
        return directory;
    }

    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = (version == null || version.isBlank()) ? "1.0.0" : version.strip();
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category != null ? category : "";
    }

    public SkillSource getSource() {
        return source;
    }

    public void setSource(SkillSource source) {
        this.source = source != null ? source : SkillSource.USER;
    }

    public boolean isUserModified() {
        return userModified;
    }

    public void setUserModified(boolean userModified) {
        this.userModified = userModified;
    }

    public List<String> getPlatforms() {
        return platforms;
    }

    public void setPlatforms(List<String> platforms) {
        this.platforms = platforms != null ? platforms : new ArrayList<>();
    }

    public List<String> getRequiresToolGroups() {
        return requiresToolGroups;
    }

    public void setRequiresToolGroups(List<String> requiresToolGroups) {
        this.requiresToolGroups = requiresToolGroups != null ? requiresToolGroups : new ArrayList<>();
    }

    public List<String> getFallbackForToolGroups() {
        return fallbackForToolGroups;
    }

    public void setFallbackForToolGroups(List<String> fallbackForToolGroups) {
        this.fallbackForToolGroups = fallbackForToolGroups != null ? fallbackForToolGroups : new ArrayList<>();
    }
}
