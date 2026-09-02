package com.javaclaw.extension.spi;

import java.util.List;

/** 为 Schedule 提供当前 Workspace 中可执行 Definition 精确版本的扩展回调。 */
@FunctionalInterface
public interface SchedulableDefinitionProvider {
    /**
     * 读取当前扩展的可调度 Definition。
     *
     * @param context 目标扩展自己的受限执行上下文
     * @return 按稳定标识排序的不可变条目
     * @throws Exception 托管存储读取失败
     */
    List<ScheduleTargetCatalogPort.DefinitionEntry> list(ExtensionExecutionContext context) throws Exception;
}
