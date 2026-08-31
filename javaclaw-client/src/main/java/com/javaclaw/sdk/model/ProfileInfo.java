package com.javaclaw.sdk.model;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 版本化执行 Profile；用于请求服务端解析，不允许客户端直接指定最终 cwd 或 SandboxPolicy。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param name 展示名称或资源名称；有效服务端响应中非空
 * @param kind 稳定类型标记；读取方应保留或忽略未知种类
 * @param provider 云模型 Provider 标识，不含凭据
 * @param model 对话模型标识
 * @param systemPrompt Profile 系统提示词
 * @param enabledTools 可见工具名集合；null 归一为空集合并复制
 * @param requestedSandboxMode Profile 请求的沙箱上限，不表示已经授权
 * @param maxIterations 单 Turn 迭代次数预算
 * @param maxModelCalls 单 Turn 模型调用次数预算
 * @param attributes Profile 扩展属性快照；null 归一为空 Map，不应含凭据
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record ProfileInfo(
        String id,
        String name,
        String kind,
        String provider,
        String model,
        String systemPrompt,
        Set<String> enabledTools,
        String requestedSandboxMode,
        int maxIterations,
        int maxModelCalls,
        Map<String, String> attributes,
        long revision,
        Instant updatedAt) {
    /** 复制工具集合与扩展属性；其他字段保留服务端表示，权限验证由保存/解析服务执行。 */
    public ProfileInfo {
        enabledTools = enabledTools == null ? Set.of() : Set.copyOf(enabledTools);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
