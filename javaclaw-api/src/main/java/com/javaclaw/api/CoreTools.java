package com.javaclaw.api;

import java.util.Set;

/** JavaClaw Turn Harness 始终认识的最小 Core 工具集合。 */
public final class CoreTools {
    /** 渐进搜索冻结工具目录的模型工具名。 */
    public static final String SEARCH_NAME = "tool_search";

    /** 父 Turn 经审批应用受管子 Thread Patch 的工具名。 */
    public static final String WORKTREE_APPLY_NAME = "worktree_apply";

    /** Core 工具来源标识。 */
    public static final String PRODUCER_ID = "core";

    private static final ToolDescriptor SEARCH = new ToolDescriptor(
            new ToolIdentity(PRODUCER_ID, SEARCH_NAME, 1),
            "在本 Turn 启动时冻结的授权目录中搜索工具，不会加载新来源或扩大权限。",
            new CanonicalPayload("""
                    {"additionalProperties":false,"properties":{"limit":{"maximum":100,"minimum":1,"type":"integer"},"query":{"minLength":1,"type":"string"}},"required":["query","limit"],"type":"object"}
                    """.strip()),
            new CanonicalPayload("""
                    {"additionalProperties":false,"properties":{"tools":{"items":{"type":"object"},"type":"array"}},"required":["tools"],"type":"object"}
                    """.strip()),
            ToolRisk.READ_ONLY,
            Set.of("catalog", "discover", "search", "工具", "搜索"));

    private static final ToolDescriptor WORKTREE_APPLY = new ToolDescriptor(
            new ToolIdentity(PRODUCER_ID, WORKTREE_APPLY_NAME, 1),
            "将指定摘要的 Managed Worktree Patch 原子应用到其父 Thread Workspace；每次调用都必须人工审批。",
            new CanonicalPayload("""
                    {"additionalProperties":false,"properties":{"patchDigest":{"pattern":"^[0-9a-f]{64}$","type":"string"},"worktreeId":{"format":"uuid","type":"string"}},"required":["worktreeId","patchDigest"],"type":"object"}
                    """.strip()),
            new CanonicalPayload("""
                    {"additionalProperties":false,"properties":{"patchDigest":{"pattern":"^[0-9a-f]{64}$","type":"string"},"revision":{"minimum":1,"type":"integer"},"state":{"const":"APPLIED","type":"string"},"worktreeId":{"format":"uuid","type":"string"}},"required":["worktreeId","patchDigest","state","revision"],"type":"object"}
                    """.strip()),
            ToolRisk.WORKSPACE_WRITE,
            Set.of("git", "patch", "worktree", "apply", "合并", "补丁"));

    private CoreTools() {}

    /**
     * 返回冻结目录搜索工具的不可变描述。
     *
     * @return Core 搜索工具
     */
    public static ToolDescriptor search() {
        return SEARCH;
    }

    /**
     * 返回仅供父 Turn 使用的受治理 Patch 应用工具。
     *
     * @return Managed Worktree 应用工具
     */
    public static ToolDescriptor worktreeApply() {
        return WORKTREE_APPLY;
    }
}
