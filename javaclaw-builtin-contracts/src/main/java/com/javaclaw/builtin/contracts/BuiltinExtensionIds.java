package com.javaclaw.builtin.contracts;

/** 内置扩展的稳定标识；Protocol 与 SDK 使用同一组值。 */
public final class BuiltinExtensionIds {
    /** Plan。 */
    public static final String PLAN = "com.javaclaw.plan";
    /** Loop。 */
    public static final String LOOP = "com.javaclaw.loop";
    /** Workflow。 */
    public static final String WORKFLOW = "com.javaclaw.workflow";
    /** SDD。 */
    public static final String SDD = "com.javaclaw.sdd";
    /** Schedule。 */
    public static final String SCHEDULE = "com.javaclaw.schedule";
    /** Memory。 */
    public static final String MEMORY = "com.javaclaw.memory";
    /** Knowledge。 */
    public static final String KNOWLEDGE = "com.javaclaw.knowledge";
    /** Skill。 */
    public static final String SKILL = "com.javaclaw.skill";
    /** Site/Browser。 */
    public static final String SITE = "com.javaclaw.site";
    /** 平台 MCP Host。 */
    public static final String MCP = "com.javaclaw.mcp";
    /** 受治理的 Workspace 文件、命令、工具链与依赖准备。 */
    public static final String CODING = CodingContracts.EXTENSION_ID;

    private BuiltinExtensionIds() {}
}
