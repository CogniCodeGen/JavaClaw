package com.javaclaw.extension.spi;

/**
 * 首次创建 Extension Job 时解析并冻结提交内容的工厂。
 *
 * <p>平台完成幂等恢复检查后才调用该工厂。实现只能读取权威配置并构造不可变快照，不能执行外部副作用。
 */
@FunctionalInterface
public interface ExtensionJobSubmissionFactory {
    /**
     * 创建完整冻结输入。
     *
     * @return 待原子持久化的 Job 提交内容
     */
    ExtensionJobSubmission create() throws Exception;
}
