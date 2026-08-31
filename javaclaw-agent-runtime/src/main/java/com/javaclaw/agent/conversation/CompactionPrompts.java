package com.javaclaw.agent.conversation;

import com.javaclaw.agent.prompt.PromptCatalog;

/** 复用 PromptCatalog 中版本化压缩模板的窄入口，不建立第二套注册中心。 */
public final class CompactionPrompts {
    private static final PromptCatalog CATALOG = new PromptCatalog();

    private CompactionPrompts() {}

    /** 返回 Codex compact checkpoint 的版本化中文适配。 */
    public static String checkpoint() {
        return CATALOG.require("compaction").content();
    }

    /** 返回压缩窗口重新注入时使用的版本化 summary prefix。 */
    public static String summaryPrefix() {
        return CATALOG.require("summary_prefix").content();
    }
}
