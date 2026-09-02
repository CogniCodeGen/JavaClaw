package com.javaclaw.runtime;

import java.util.Objects;

/**
 * Thin Harness 的显式端口集合。
 *
 * @param models 模型调用
 * @param contexts 上下文组装
 * @param compactor 上下文压缩
 * @param catalogs 工具冻结与发现
 * @param tools 工具治理执行
 * @param journal 生命周期持久化
 * @param events 流式背压 sink
 */
public record TurnHarnessServices(
        ModelGateway models,
        ContextAssembler contexts,
        ContextCompactor compactor,
        ToolCatalogPort catalogs,
        GovernedToolExecutor tools,
        TurnJournal journal,
        ModelEventSink events) {
    /** 校验全部端口。 */
    public TurnHarnessServices {
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(contexts, "contexts");
        Objects.requireNonNull(compactor, "compactor");
        Objects.requireNonNull(catalogs, "catalogs");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(events, "events");
    }
}
