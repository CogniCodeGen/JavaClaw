package com.javaclaw.extension.spi;

import java.util.List;

/** 随发行版编译的内置 Extension Bundle 契约。 */
public interface ExtensionBundle extends AutoCloseable {
    /**
     * 返回不可变描述。
     *
     * @return Bundle 描述
     */
    ExtensionDescriptor descriptor();

    /**
     * 启动并返回贡献点。平台会校验唯一性与声明的一致性。
     *
     * @param context 启动上下文
     * @return 贡献点
     * @throws Exception 启动失败
     */
    List<ExtensionContribution> start(ExtensionContext context) throws Exception;

    /**
     * 返回扩展公开的 Item、query 与 command schema。
     *
     * @return 不可变 schema 列表；没有公开 schema 时为空
     */
    default List<ExtensionSchema> schemas() {
        return List.of();
    }

    /**
     * 创建该 Bundle 的可恢复 Job executor。
     *
     * <p>返回的 executor 只读取 Job 冻结输入，不得把调用期 Workspace 或权限对象保存在成员中。
     *
     * @param context 稳定运行端口
     * @return Job 类型注册；没有后台执行时为空
     */
    default List<ExtensionJobRegistration> jobExecutors(ExtensionJobRuntimeContext context) {
        return List.of();
    }

    /**
     * 在全部 Bundle 注册且平台回调端口可用后恢复 Workspace 后台状态。
     *
     * @param context Workspace 级恢复上下文
     * @throws Exception 恢复失败；App Server 必须终止启动
     */
    default void restore(ExtensionExecutionContext context) throws Exception {}

    /**
     * 停止扩展并释放资源；实现必须可重复调用。
     *
     * @throws Exception 释放失败
     */
    @Override
    void close() throws Exception;
}
