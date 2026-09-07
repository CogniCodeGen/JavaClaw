package com.javaclaw.runtime;

import java.util.Objects;

/**
 * Provider 无关的已冻结指令层；仅 Adapter 可以为不支持 developer role 的 Provider 合并文本。
 *
 * @param systemInstruction 模型基础与平台安全层，非空容器
 * @param developerInstructions Role、已确认项目约定与实际能力摘要，按此顺序冻结
 * @param responseContract 响应结构要求；可为空，不包含外部资料
 */
public record ModelInstructions(String systemInstruction, String developerInstructions, String responseContract) {
    /** 校验各层文本，不修改调用方已冻结的顺序。 */
    public ModelInstructions {
        Objects.requireNonNull(systemInstruction, "systemInstruction");
        Objects.requireNonNull(developerInstructions, "developerInstructions");
        Objects.requireNonNull(responseContract, "responseContract");
    }
}
