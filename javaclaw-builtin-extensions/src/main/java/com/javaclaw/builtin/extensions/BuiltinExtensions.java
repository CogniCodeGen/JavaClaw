package com.javaclaw.builtin.extensions;

import java.util.List;

import com.javaclaw.extension.spi.ExtensionBundle;

/** 随 JavaClaw 6 发行并显式装配的内置 Bundle 清单。 */
public final class BuiltinExtensions {
    private BuiltinExtensions() {}

    /**
     * 创建一组尚未启动的 Bundle；调用方拥有其关闭责任。
     *
     * @return 固定顺序的内置扩展
     */
    public static List<ExtensionBundle> create() {
        return List.of(
                new PlanExtension(),
                new LoopExtension(),
                new WorkflowExtension(),
                new SddExtension(),
                new ScheduleExtension(),
                new MemoryExtension(),
                new KnowledgeExtension(),
                new SkillExtension(),
                new SiteExtension(),
                new CodingExtension());
    }
}
