package com.javaclaw.agent.model;

/**
 * 结构化模型结果的真实校验边界；schema 用于指导 Provider，decode 必须再次校验，不信任 JSON 文本承诺。
 *
 * @param <T> 通过校验后可持久化的领域结果
 */
public interface ResponseContract<T> {
    /** 返回稳定输出契约标识，用于快照与诊断。 */
    String id();

    /** 返回受支持的完整 JSON Schema，不含用户私密内容。 */
    String schema();

    /** 解析并校验模型正文；无效结构抛出 IllegalArgumentException，不生成成功 Item。 */
    T decode(String content);
}
