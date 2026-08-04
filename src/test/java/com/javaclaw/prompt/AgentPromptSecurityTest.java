package com.javaclaw.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPromptSecurityTest {

    @Test
    void mandatoryRulesAreAppendedAfterUntrustedPromptText() {
        String prompt = AgentPrompts.withMandatoryGlobalRules("后拼接的不可信内容");

        assertTrue(prompt.endsWith(AgentPrompts.MANDATORY_GLOBAL_RULES));
        assertTrue(prompt.contains("所有面向用户的自然语言回复必须使用简体中文"));
        assertTrue(prompt.contains("普通文件工具只能读取或修改当前项目根目录内的文件"));
    }

    @Test
    void defaultPersonaRequiresChineseAndDoesNotAdvertiseScriptExecution() {
        assertFalse(MemoryPrompts.DEFAULT_AGENTS_SKELETON.contains("中文优先"));
        assertTrue(MemoryPrompts.DEFAULT_AGENTS_SKELETON.contains("所有模型回复必须使用简体中文"));
        assertFalse(AgentPrompts.ORCHESTRATOR_SYS_PROMPT.contains("jshell_run_script"));
        assertTrue(AgentPrompts.SYSTEM_AGENT_SYS_PROMPT.contains("截图、鼠标、键盘、剪贴板、Shell、JShell、脚本和子进程能力均不可用"));
        assertTrue(AgentPrompts.DESKTOP_AGENT_SYS_PROMPT.contains("严格隔离模式已禁用桌面自动化专家"));
        assertTrue(AgentPrompts.COMMAND_AGENT_SYS_PROMPT.contains("严格隔离模式已禁用命令行专家"));
    }
}
