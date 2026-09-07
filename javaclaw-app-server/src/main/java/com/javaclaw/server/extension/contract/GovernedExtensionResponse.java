package com.javaclaw.server.extension.contract;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ToolExecutionFact;
import com.javaclaw.extension.spi.ExtensionResponse;

/**
 * 扩展响应与平台独立产生的副作用事实；扩展 Handler 无权构造平台 collector。
 *
 * @param response 扩展 Schema 响应
 * @param facts 可信平台事实，最多 256 条
 * @param success 工具操作是否成功；非零进程退出码必须为 false
 */
public record GovernedExtensionResponse(ExtensionResponse response, List<ToolExecutionFact> facts, boolean success) {
    /** 创建成功的扩展响应，保留既有无平台事实调用语义。 */
    public GovernedExtensionResponse(ExtensionResponse response, List<ToolExecutionFact> facts) {
        this(response, facts, true);
    }

    /** 复制事实并限制单次事务大小。 */
    public GovernedExtensionResponse {
        Objects.requireNonNull(response, "response");
        facts = List.copyOf(facts);
        if (facts.size() > 256) {
            throw new IllegalArgumentException("平台事实超过单次工具限制");
        }
    }
}
