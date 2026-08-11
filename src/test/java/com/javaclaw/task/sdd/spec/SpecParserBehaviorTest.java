package com.javaclaw.task.sdd.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecParserBehaviorTest {

    @Test
    void proposalParsingIsNullSafeAndStopsAtTheNextHeading() {
        assertEquals(new Proposal("", "", ""), SpecParser.parseProposal(null));
        assertEquals(new Proposal("", "", ""), SpecParser.parseProposal("  "));

        Proposal proposal = SpecParser.parseProposal("""
                # 变更
                ## 为什么
                解决重复执行。
                保证幂等。
                ## 改什么：调度
                引入统一执行器。
                ## 不改什么
                _（无）_
                # 附录
                不应进入正文。
                """);

        assertEquals("解决重复执行。\n保证幂等。", proposal.why());
        assertEquals("引入统一执行器。", proposal.whatChanges());
        assertEquals("", proposal.outOfScope());
        assertEquals("边界", SpecParser.parseProposal("## 不改什么\n边界").outOfScope());
    }

    @Test
    void capabilityParsingRecoversMixedMarkdownAndFlushesEveryScenario() {
        assertEquals("fallback", SpecParser.parseCapabilitySpec(null, "fallback").name());
        Capability capability = SpecParser.parseCapabilitySpec("""
                # 能力：调度执行
                ## 需求：按时运行
                ### 场景：成功
                - **Given** 已启用
                * **when:** 到达时间
                - **THEN：** 启动任务
                - 判据：[output_contains] started
                ### 场景：失败回退
                Given: 后端不可用
                When 保存任务
                Then 保持暂停
                判据：仍可重试
                ## 需求：可恢复
                ### 场景：重启
                Given 已有定义
                When 应用重启
                Then 恢复调度
                """, "fallback");

        assertEquals("调度执行", capability.name());
        assertEquals(2, capability.requirements().size());
        assertEquals(3, capability.allScenarios().size());
        Scenario success = capability.allScenarios().getFirst();
        assertEquals("已启用", success.given());
        assertEquals("到达时间", success.when());
        assertEquals("启动任务", success.then());
        assertEquals(Criterion.OUTPUT_CONTAINS, success.criterion().type());
        assertEquals("started", success.criterion().predicate());
        Scenario fallback = capability.allScenarios().get(1);
        assertEquals(Criterion.FREEFORM, fallback.criterion().type());
        assertEquals("仍可重试", fallback.criterion().predicate());
        Scenario restart = capability.allScenarios().get(2);
        assertEquals("恢复调度", restart.criterion().predicate());

        Capability partial = SpecParser.parseCapabilitySpec("""
                ### 场景：无所属需求
                Given 前置
                ## 需求：空需求
                """, "fallback");
        assertEquals("fallback", partial.name());
        assertEquals(1, partial.requirements().size());
        assertTrue(partial.requirements().getFirst().scenarios().isEmpty());
    }

    @Test
    void taskParsingHandlesCheckboxesFilesCriteriaAndNoise() {
        assertTrue(SpecParser.parseTasks(null).isEmpty());
        assertTrue(SpecParser.parseTasks(" ").isEmpty());
        List<TaskItem> tasks = SpecParser.parseTasks("""
                # 实现计划
                - [ ] 1. 新增执行器（A.java, B.java） — 判据：测试通过
                * [X] 2、更新文档（README.md， docs/guide.md） 判据:内容完整
                - [x] 3 动作中提到判据但没有冒号
                - [ ] 4. 无文件（ ）
                - [ ] 5. 保留普通括号(files.txt)
                - not a task
                """);

        assertEquals(5, tasks.size());
        assertFalse(tasks.getFirst().done());
        assertEquals(List.of("A.java", "B.java"), tasks.getFirst().files());
        assertEquals("测试通过", tasks.getFirst().criterion());
        assertTrue(tasks.get(1).done());
        assertEquals(List.of("README.md", "docs/guide.md"), tasks.get(1).files());
        assertEquals("内容完整", tasks.get(1).criterion());
        assertEquals("动作中提到判据但没有冒号", tasks.get(2).action());
        assertEquals(List.of(), tasks.get(3).files());
        assertEquals("保留普通括号(files.txt)", tasks.get(4).action());
    }

    @Test
    void criterionParsingNormalizesInlineAndFreeformForms() {
        assertEquals(Criterion.freeform(""), SpecParser.parseCriterion(null));
        assertEquals(Criterion.freeform(""), SpecParser.parseCriterion(" "));
        assertEquals(new Criterion(Criterion.ARTIFACT_EXISTS, "target/app.jar"),
                SpecParser.parseCriterion("[ artifact_exists ] target/app.jar"));
        assertEquals(new Criterion(Criterion.FREEFORM, "人工验收"),
                SpecParser.parseCriterion("[] 人工验收"));
        assertEquals(Criterion.freeform("输出合理"), SpecParser.parseCriterion(" 输出合理 "));
    }
}
