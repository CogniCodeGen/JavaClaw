package com.javaclaw.skill;

import com.javaclaw.agent.model.ToolResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

/**
 * 技能按需读取工具 —— 渐进式暴露的 L2 拉取入口。
 *
 * <p>系统提示词中常驻的「可用技能目录」(L1) 只含技能名称与用途；当模型判断某技能与当前任务
 * 相关、但本轮路由未预载其正文时，调用本工具按名称拉取该技能的详细指令（{@code SKILL.md}
 * 正文 + references）。设计上类比 {@code mcp_call_tool}：上下文里只放轻量目录，完整内容现需现取。</p>
 *
 * <p>无状态：每次调用实时查询 {@link SkillManager} 单例，故可在 toolkit 中以单实例长期注册。</p>
 *
 * @author JavaClaw
 */
public final class SkillTools {

    private static final Logger log = LoggerFactory.getLogger(SkillTools.class);

    @Tool(name = "skill_read",
            description = "按名称读取一个技能的详细指令（正文 + 参考文档）。" +
                    "当系统提示词的「可用技能目录」中列出了某技能、但未提供其详细内容，" +
                    "且该技能与当前任务相关时，调用本工具拉取其完整指令后再据此执行。" +
                    "skill_name 必须与目录中展示的技能名称完全一致。" +
                    "可选 path 参数：只读取该技能的某一份参考文档（填目录中列出的文件名），进一步节省上下文。")
    public String readSkill(
            @ToolParam(name = "skill_name",
                    description = "要读取的技能名称，须与「可用技能目录」中展示的名称完全一致") String skillName,
            @ToolParam(name = "path",
                    description = "可选：只读取该技能 references/ 下的某个参考文档（填文件名，如 api-doc.md）",
                    required = false) String path) {
        String name = skillName == null ? "" : skillName.strip();
        if (name.isEmpty()) {
            return ToolResponse.error("skill_read", "skill_name 为空，请指定要读取的技能名称。");
        }

        SkillManager mgr = SkillManager.getInstance();

        // L2 文件级：仅拉取指定参考文档
        if (path != null && !path.isBlank()) {
            String refDetail = mgr.buildReferenceDetail(name, path);
            if (refDetail == null) {
                return ToolResponse.error("skill_read",
                        "未找到技能「" + name + "」的参考文档「" + path + "」（或非文本文件）。");
            }
            log.info("skill_read 载入参考文档: {}/{}", name, path);
            SkillUsageTracker.getInstance().recordSkillRead(name);
            return ToolResponse.success("skill_read",
                    "已载入技能「" + name + "」的参考文档「" + path + "」：\n\n" + refDetail);
        }

        String detail = mgr.buildSkillDetail(name);
        if (detail == null) {
            String available = mgr.getEnabledSkills().stream()
                    .map(Skill::getName)
                    .collect(Collectors.joining("、"));
            log.warn("skill_read 未找到技能: {}", name);
            return ToolResponse.error("skill_read",
                    "未找到名为「" + name + "」的已启用技能。当前可用技能："
                            + (available.isEmpty() ? "（无）" : available));
        }

        log.info("skill_read 载入技能正文: {}", name);
        SkillUsageTracker.getInstance().recordSkillRead(name);
        return ToolResponse.success("skill_read",
                "已载入技能「" + name + "」的详细指令，请据此执行：\n\n" + detail);
    }
}
