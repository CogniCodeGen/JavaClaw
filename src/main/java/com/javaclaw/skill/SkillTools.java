package com.javaclaw.skill;

import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.RunResourceScope;
import com.javaclaw.util.TokenEstimator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 技能按需读取工具 —— 渐进式暴露的 L1/L2 拉取入口。
 *
 * <p>系统提示词中常驻的「可用技能目录」(L0) 只含技能名称与用途；当模型判断某技能与当前任务
 * 相关、但本轮路由未预载其正文时，调用本工具按名称拉取该技能的详细指令（{@code SKILL.md}
 * 正文与参考文件索引）。参考资料必须再以 path 单份读取，不会随正文自动展开。</p>
 *
 * <p>实例绑定工作区技能仓库，toolkit 与工作区 Context 共同回收。</p>
 *
 * @author JavaClaw
 */
@com.javaclaw.framework.spi.ToolContract(
        group = "skill", permissions = {"tool.read"}, idempotent = true,
        resultClass = com.javaclaw.framework.spi.ToolResultClass.SELF_BOUNDED)
public final class SkillTools {

    private static final Logger log = LoggerFactory.getLogger(SkillTools.class);
    /** Reserves room for the stable tool-status prefix and next_cursor marker. */
    private static final int PAGE_CHARACTERS = 7_900;
    private static final int SEARCH_CHARACTERS = 7_000;
    private static final int CATALOG_PAGE_TOKENS = 1_200;
    private final SkillManager skills;
    private final SkillUsageTracker usage;
    private final SkillPromptRenderer.CatalogSnapshot catalog;
    private final SkillReferenceReadSession referenceReads;

    public SkillTools(SkillManager skills, SkillUsageTracker usage) {
        this(skills, usage, null);
    }

    public SkillTools(SkillManager skills, SkillUsageTracker usage, Set<String> availableGroups) {
        this(skills, usage, availableGroups, null);
    }

    public SkillTools(
            SkillManager skills, SkillUsageTracker usage, Set<String> availableGroups,
            RunResourceScope runResources) {
        this(skills, usage, availableGroups, null, runResources);
    }

    public SkillTools(
            SkillManager skills, SkillUsageTracker usage, Set<String> availableGroups,
            RunId runId, RunResourceScope runResources) {
        this.skills = java.util.Objects.requireNonNull(skills, "skills");
        this.usage = java.util.Objects.requireNonNull(usage, "usage");
        this.catalog = runResources == null
                ? skills.readableCatalogSnapshot(availableGroups)
                : runId == null
                    ? skills.readableCatalogSnapshot(runResources, availableGroups)
                    : skills.readableCatalogSnapshot(runId, runResources, availableGroups);
        this.referenceReads = runResources == null
                ? new SkillReferenceReadSession()
                : runResources.getOrCreate(
                        SkillReferenceReadSession.RESOURCE_KEY,
                        SkillReferenceReadSession.class,
                        SkillReferenceReadSession::new);
    }

    @Tool(name = "skill_read",
            description = "渐进读取技能。无 path 时读取 SKILL.md 正文和参考文件名索引；" +
                    "有 path 时只读取指定的一份 references 文档，不会自动展开其他参考资料。" +
                    "query 可在 references 中执行同一行关键词 AND 搜索，line 可读取搜索命中的行附近。" +
                    "超过 256 KiB 的参考文件必须先 query 搜索，再用返回的行号读取。" +
                    "当系统提示词的「可用技能目录」中列出了某技能、但未提供其详细内容，" +
                    "且该技能与当前任务相关时，调用本工具后再据此执行。" +
                    "skill_name 使用目录中展示的名称或读取键；填 * 时分页读取技能目录，且 path 必须为空。" +
                    "单次最多返回 8000 字符；若返回 next_cursor，用同一参数继续分页。")
    public String readSkill(
            @ToolParam(
                    description = "要读取的技能名称或目录中的读取键；填 * 可分页读取剩余目录") String skillName,
            @ToolParam(
                    description = "可选：只读取该技能 references/ 下的某个参考文档（填文件名，如 api-doc.md）",
                    required = false) String path,
            @ToolParam(
                    description = "可选：分页游标；首次读取填 0，后续使用上次返回的 next_cursor",
                    required = false) Integer cursor,
            @ToolParam(
                    description = "可选：在 references 中搜索的空格分隔关键词；所有关键词必须出现在同一行",
                    required = false) String query,
            @ToolParam(
                    description = "可选：query 返回的 1-based 行号；与非零 cursor 不能同时使用",
                    required = false) Integer line) {
        String name = skillName == null ? "" : skillName.strip();
        if (name.isEmpty()) {
            return ToolResponse.error("skill_read", "skill_name 为空，请指定要读取的技能名称。");
        }
        int offset = cursor == null ? 0 : cursor;
        if (offset < 0) {
            return ToolResponse.error("skill_read", "cursor 不能为负数。");
        }

        SkillManager mgr = skills;
        if (name.equals("*")) {
            if ((path != null && !path.isBlank())
                    || (query != null && !query.isBlank()) || line != null) {
                return ToolResponse.error(
                        "skill_read", "目录分页模式不接受 path、query 或 line 参数。");
            }
            int payloadBudget = CATALOG_PAGE_TOKENS
                    - TokenEstimator.estimate(ToolResponse.success("skill_read", ""));
            SkillCatalogPage page = mgr.readCatalogPage(catalog, offset, payloadBudget);
            if (page == null) {
                return ToolResponse.error("skill_read", "目录分页游标无效，请从 cursor=0 重新读取。");
            }
            log.info("skill_read 目录分页: {}", offset);
            String response = ToolResponse.success("skill_read", page.content());
            if (TokenEstimator.estimate(response) > CATALOG_PAGE_TOKENS) {
                throw new IllegalStateException("skill catalog tool response exceeded token budget");
            }
            return response;
        }

        String resolvedName = catalog.resolveLookup(name);
        if (resolvedName == null) {
            log.warn("skill_read 未找到本轮激活目标: {}/{}@{}", name, path, offset);
            return ToolResponse.error("skill_read",
                    "未找到该本轮可用技能。请调用 skill_read(\"*\", cursor=0) 查看有界目录。");
        }
        if (query != null && !query.isBlank()) {
            if (offset != 0 || line != null) {
                return ToolResponse.error(
                        "skill_read", "搜索模式不接受非零 cursor 或 line 参数。");
            }
            SkillReferenceSearchResult result = mgr.searchReferences(
                    catalog, name, path, query, SEARCH_CHARACTERS, referenceReads);
            if (result == null) {
                return ToolResponse.error("skill_read", "未找到目标参考文件。");
            }
            if (!result.error().isBlank()) {
                return ToolResponse.error("skill_read", result.error());
            }
            log.info("skill_read 参考搜索: {}/{} query={}", resolvedName, path, query);
            usage.recordSkillRead(resolvedName);
            return ToolResponse.success("skill_read", result.content());
        }
        if (line != null && offset != 0) {
            return ToolResponse.error(
                    "skill_read", "line 与非零 cursor 不能同时使用。");
        }
        SkillContentPage page = mgr.readProgressivePage(
                catalog, name, path, offset, line, PAGE_CHARACTERS, referenceReads);
        if (page == null) {
            log.warn("skill_read 未找到目标: {}/{}@{}", resolvedName, path, offset);
            return ToolResponse.error("skill_read",
                    "未找到目标参考文件或分页位置；请检查 path，或从 cursor=0 重新读取。");
        }
        if (page.searchRequired()) {
            return ToolResponse.error("skill_read",
                    "该参考文件超过 256 KiB，请先提供 query 搜索，再用返回的 line 定位读取。");
        }
        if (!page.error().isBlank()) {
            return ToolResponse.error("skill_read", page.error());
        }

        log.info("skill_read 渐进读取: {}/{}@{}", resolvedName, path, offset);
        usage.recordSkillRead(resolvedName);
        String cursorHint = page.hasMore()
                ? "\n\n[next_cursor=" + page.nextCursor() + "]"
                : "\n\n[next_cursor=END]";
        return ToolResponse.success("skill_read",
                page.content() + cursorHint);
    }

    /** Source-compatible entry point for callers outside Spring AI tool reflection. */
    public String readSkill(String skillName, String path) {
        return readSkill(skillName, path, 0, null, null);
    }

    /** Source-compatible paging entry point. */
    public String readSkill(String skillName, String path, Integer cursor) {
        return readSkill(skillName, path, cursor, null, null);
    }
}
