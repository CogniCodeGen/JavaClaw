package com.javaclaw.skill;

import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.config.AgentConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能自管理工具集 —— 智能体的「程序性记忆」写入口（借鉴 hermes-agent skill_manage）。
 *
 * <p>让智能体在以下时机把经验沉淀为技能：完成复杂任务（≥5 次工具调用）后、
 * 踩坑后找到可行路径时、被用户纠正方法时、发现非平凡工作流时。
 * 六个动作与 Hermes 对齐：create / patch（推荐，token 高效）/ edit / delete / write_file / remove_file。</p>
 *
 * <p>所有写动作受 {@code skill.evolution.mode} 三态闸门约束：
 * <ul>
 *   <li>{@code off} — 拒绝写入</li>
 *   <li>{@code suggest}（默认）— 提案入待审队列，经用户审阅后落盘</li>
 *   <li>{@code auto} — 直接落盘（NOTIFY 级 Toast 由 ToolConfirmationManager 自动展示）</li>
 * </ul>
 * user-modified 保护：用户修改过的技能即使在 auto 模式下也不被静默覆盖，强制走提案。</p>
 *
 * <p>无状态：每次调用实时查询 {@link SkillManager} 单例与 {@link AgentConfig}。</p>
 *
 * @author JavaClaw
 */
public final class SkillManageTools {

    private static final Logger log = LoggerFactory.getLogger(SkillManageTools.class);

    /**
     * 提案接收器：suggest 模式（及 auto 模式下的 user-modified 保护降级）时，
     * 写动作不直接落盘，而是把提案交给接收器排队待审。
     * 由自学习闭环初始化时注入（SkillProposalQueue）；未注入时 suggest 暂按 auto 直落盘。
     */
    public interface ProposalSink {
        /**
         * 提交一份技能变更提案
         *
         * @param request 结构化变更请求（采纳后经 {@link SkillChangeRequest#apply()} 落盘）
         * @return 提案 ID；同指纹去重 / 被拒冷却拦截时返回 null
         */
        String submit(SkillChangeRequest request);
    }

    private static volatile ProposalSink proposalSink;

    /** 注入提案接收器（自学习闭环装配时调用） */
    public static void setProposalSink(ProposalSink sink) {
        proposalSink = sink;
    }

    // ==================== 工具实现 ====================

    @Tool(name = "skill_create",
            description = "创建一个新技能，把本次任务中验证可行的非平凡工作流沉淀为可复用的程序性记忆。" +
                    "适用时机：完成了 5 次以上工具调用的复杂任务、踩坑后找到了可行路径、被用户纠正了做法。" +
                    "content 应按「适用场景 → 操作步骤 → 注意事项 → 验证方法」组织。" +
                    "若已有相近技能，优先用 skill_patch 修补而非新建。")
    public String createSkill(
            @ToolParam(name = "name", description = "技能名称（简短、能表达用途）") String name,
            @ToolParam(name = "description", description = "技能描述：一句话说明何时该用这个技能") String description,
            @ToolParam(name = "content", description = "技能正文（Markdown）：按「适用场景→操作步骤→注意事项→验证方法」组织") String content,
            @ToolParam(name = "category", description = "分类（如：编码/浏览器/系统/办公），可为空", required = false) String category,
            @ToolParam(name = "tags", description = "标签，逗号分隔，可为空", required = false) String tags) {
        String mode = evolutionMode();
        if ("off".equals(mode)) {
            return refuseOff("skill_create");
        }
        if (name == null || name.isBlank() || content == null || content.isBlank()) {
            return ToolResponse.error("skill_create", "name 与 content 不能为空。");
        }
        String skillName = name.strip();
        if (SkillManager.getInstance().getSkillByName(skillName) != null) {
            return ToolResponse.error("skill_create",
                    "技能「" + skillName + "」已存在，请改用 skill_patch 定向修补或 skill_edit 整篇重写。");
        }

        if (shouldPropose(mode, false)) {
            SkillChangeRequest req = new SkillChangeRequest();
            req.action = "create";
            req.skillName = skillName;
            req.description = safe(description);
            req.category = safe(category);
            req.tags = splitTags(tags);
            req.content = content;
            req.reason = "智能体请求创建新技能「" + skillName + "」";
            return propose("skill_create", req);
        }

        Skill skill = SkillManager.getInstance().createAgentSkill(
                skillName, safe(description), content, safe(category), splitTags(tags));
        if (skill == null) {
            return ToolResponse.error("skill_create", "技能「" + skillName + "」创建失败（同名技能已存在）。");
        }
        log.info("智能体已创建技能: {} (v{})", skillName, skill.getVersion());
        return ToolResponse.success("skill_create",
                "已创建技能「" + skillName + "」(v" + skill.getVersion() + ")，后续相关任务将自动可用。");
    }

    @Tool(name = "skill_patch",
            description = "对既有技能正文做定向修补（old_string 精确替换为 new_string），" +
                    "用于把新发现的注意事项、更优步骤合入技能。这是更新技能的推荐方式（token 高效）。" +
                    "old_string 必须与技能正文中的片段完全一致且唯一；不确定当前内容时先用 skill_read 查看。")
    public String patchSkill(
            @ToolParam(name = "skill_name", description = "目标技能名称，须与「可用技能目录」中展示的名称一致") String skillName,
            @ToolParam(name = "old_string", description = "要被替换的原文片段（须在正文中唯一）") String oldString,
            @ToolParam(name = "new_string", description = "替换后的新内容") String newString,
            @ToolParam(name = "reason", description = "本次修补的理由（一句话）", required = false) String reason) {
        String mode = evolutionMode();
        if ("off".equals(mode)) {
            return refuseOff("skill_patch");
        }
        Skill skill = SkillManager.getInstance().getSkillByName(strip(skillName));
        if (skill == null) {
            return ToolResponse.error("skill_patch", "未找到名为「" + strip(skillName) + "」的技能。");
        }

        boolean protectedSkill = skill.isUserModified();
        if (shouldPropose(mode, protectedSkill)) {
            SkillChangeRequest req = new SkillChangeRequest();
            req.action = "patch";
            req.skillName = skill.getName();
            req.oldString = oldString;
            req.newString = newString;
            req.reason = safe(reason).isBlank() ? "智能体请求修补技能「" + skill.getName() + "」" : reason;
            req.userModifiedWarning = protectedSkill;
            return propose("skill_patch", req);
        }

        String error = SkillManager.getInstance().applyPatch(skill.getName(), oldString, newString);
        if (error != null) {
            return ToolResponse.error("skill_patch", error + "。");
        }
        return ToolResponse.success("skill_patch",
                "已修补技能「" + skill.getName() + "」，当前版本 v"
                        + SkillManager.getInstance().getSkillByName(skill.getName()).getVersion() + "。");
    }

    @Tool(name = "skill_edit",
            description = "整篇重写既有技能的正文（结构性变更时使用）。" +
                    "仅在 skill_patch 无法表达的大规模调整时使用；小修小补请用 skill_patch。")
    public String editSkill(
            @ToolParam(name = "skill_name", description = "目标技能名称") String skillName,
            @ToolParam(name = "new_content", description = "完整的新正文（Markdown），将整体替换原正文") String newContent,
            @ToolParam(name = "reason", description = "重写理由（一句话）", required = false) String reason) {
        String mode = evolutionMode();
        if ("off".equals(mode)) {
            return refuseOff("skill_edit");
        }
        Skill skill = SkillManager.getInstance().getSkillByName(strip(skillName));
        if (skill == null) {
            return ToolResponse.error("skill_edit", "未找到名为「" + strip(skillName) + "」的技能。");
        }

        boolean protectedSkill = skill.isUserModified();
        if (shouldPropose(mode, protectedSkill)) {
            SkillChangeRequest req = new SkillChangeRequest();
            req.action = "edit";
            req.skillName = skill.getName();
            req.content = newContent;
            req.reason = safe(reason).isBlank() ? "智能体请求重写技能「" + skill.getName() + "」" : reason;
            req.userModifiedWarning = protectedSkill;
            return propose("skill_edit", req);
        }

        String error = SkillManager.getInstance().applyEdit(skill.getName(), newContent);
        if (error != null) {
            return ToolResponse.error("skill_edit", error + "。");
        }
        return ToolResponse.success("skill_edit",
                "已重写技能「" + skill.getName() + "」，当前版本 v"
                        + SkillManager.getInstance().getSkillByName(skill.getName()).getVersion() + "。");
    }

    @Tool(name = "skill_delete",
            description = "删除一个技能（整个技能目录，含版本历史，不可恢复）。" +
                    "仅在技能内容已完全过时或确认有害时使用；内容部分过时请优先用 skill_patch 修正。")
    public String deleteSkill(
            @ToolParam(name = "skill_name", description = "要删除的技能名称") String skillName,
            @ToolParam(name = "reason", description = "删除理由（一句话）", required = false) String reason) {
        String mode = evolutionMode();
        if ("off".equals(mode)) {
            return refuseOff("skill_delete");
        }
        Skill skill = SkillManager.getInstance().getSkillByName(strip(skillName));
        if (skill == null) {
            return ToolResponse.error("skill_delete", "未找到名为「" + strip(skillName) + "」的技能。");
        }

        // 删除是破坏性动作：suggest 模式与 user-modified 技能一律走提案
        if (shouldPropose(mode, skill.isUserModified())) {
            SkillChangeRequest req = new SkillChangeRequest();
            req.action = "delete";
            req.skillName = skill.getName();
            req.reason = safe(reason).isBlank() ? "智能体请求删除技能「" + skill.getName() + "」" : reason;
            req.userModifiedWarning = skill.isUserModified();
            return propose("skill_delete", req);
        }

        SkillManager.getInstance().deleteSkill(skill.getId());
        return ToolResponse.success("skill_delete", "已删除技能「" + skill.getName() + "」。");
    }

    @Tool(name = "skill_write_file",
            description = "向技能目录写入支持文件（如 references/ 下的参考文档、assets/ 下的模板）。" +
                    "references/ 中的文本文件会自动随技能正文一起注入。rel_path 相对技能根目录，如 references/api-notes.md。")
    public String writeFile(
            @ToolParam(name = "skill_name", description = "目标技能名称") String skillName,
            @ToolParam(name = "rel_path", description = "相对技能根目录的文件路径，如 references/notes.md") String relPath,
            @ToolParam(name = "file_content", description = "文件内容") String fileContent) {
        String mode = evolutionMode();
        if ("off".equals(mode)) {
            return refuseOff("skill_write_file");
        }
        Skill skill = SkillManager.getInstance().getSkillByName(strip(skillName));
        if (skill == null) {
            return ToolResponse.error("skill_write_file", "未找到名为「" + strip(skillName) + "」的技能。");
        }

        if (shouldPropose(mode, skill.isUserModified())) {
            SkillChangeRequest req = new SkillChangeRequest();
            req.action = "write_file";
            req.skillName = skill.getName();
            req.relPath = relPath;
            req.fileContent = fileContent;
            req.reason = "智能体请求向技能「" + skill.getName() + "」写入支持文件 " + relPath;
            req.userModifiedWarning = skill.isUserModified();
            return propose("skill_write_file", req);
        }

        String error = SkillManager.getInstance().writeSupportFile(skill.getName(), relPath, fileContent);
        if (error != null) {
            return ToolResponse.error("skill_write_file", error + "。");
        }
        return ToolResponse.success("skill_write_file",
                "已写入技能「" + skill.getName() + "」的支持文件 " + relPath + "。");
    }

    @Tool(name = "skill_remove_file",
            description = "删除技能目录中的支持文件（不可删除 SKILL.md 本体）。")
    public String removeFile(
            @ToolParam(name = "skill_name", description = "目标技能名称") String skillName,
            @ToolParam(name = "rel_path", description = "相对技能根目录的文件路径") String relPath) {
        String mode = evolutionMode();
        if ("off".equals(mode)) {
            return refuseOff("skill_remove_file");
        }
        Skill skill = SkillManager.getInstance().getSkillByName(strip(skillName));
        if (skill == null) {
            return ToolResponse.error("skill_remove_file", "未找到名为「" + strip(skillName) + "」的技能。");
        }

        if (shouldPropose(mode, skill.isUserModified())) {
            SkillChangeRequest req = new SkillChangeRequest();
            req.action = "remove_file";
            req.skillName = skill.getName();
            req.relPath = relPath;
            req.reason = "智能体请求删除技能「" + skill.getName() + "」的支持文件 " + relPath;
            req.userModifiedWarning = skill.isUserModified();
            return propose("skill_remove_file", req);
        }

        String error = SkillManager.getInstance().removeSupportFile(skill.getName(), relPath);
        if (error != null) {
            return ToolResponse.error("skill_remove_file", error + "。");
        }
        return ToolResponse.success("skill_remove_file",
                "已删除技能「" + skill.getName() + "」的支持文件 " + relPath + "。");
    }

    // ==================== 内部辅助 ====================

    private static String evolutionMode() {
        return AgentConfig.getInstance().getSkillEvolutionMode();
    }

    /**
     * 判定本次写动作是否应走提案而非直接落盘：
     * suggest 模式一律提案；auto 模式下 user-modified 技能强制降级为提案（绝不静默覆盖用户成果）。
     * 提案接收器未注入时回落为直接落盘（自学习闭环未装配的过渡期行为）。
     */
    private static boolean shouldPropose(String mode, boolean userModifiedTarget) {
        if (proposalSink == null) {
            return false;
        }
        return "suggest".equals(mode) || userModifiedTarget;
    }

    private static String propose(String toolName, SkillChangeRequest request) {
        String proposalId = proposalSink.submit(request);
        if (proposalId == null) {
            return ToolResponse.success(toolName,
                    "相同变更近期已提案或被用户拒绝（冷却中），本次不再重复提交。");
        }
        log.info("技能变更提案已入队: [{}] {} ({})", request.action, request.skillName, proposalId);
        return ToolResponse.success(toolName,
                "技能变更提案已提交待用户审阅（提案 " + proposalId + "）。"
                        + (request.userModifiedWarning ? "目标技能被用户修改过，需用户确认后才会生效。" : "")
                        + "无需等待，继续当前任务即可。");
    }

    private static String refuseOff(String tool) {
        return ToolResponse.error(tool, "技能自进化已关闭（skill.evolution.mode=off），不允许变更技能。");
    }

    private static List<String> splitTags(String tags) {
        List<String> result = new ArrayList<>();
        if (tags == null || tags.isBlank()) {
            return result;
        }
        for (String part : tags.split("[,，]")) {
            if (!part.isBlank()) {
                result.add(part.strip());
            }
        }
        return result;
    }

    private static String safe(String s) {
        return s == null ? "" : s.strip();
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
