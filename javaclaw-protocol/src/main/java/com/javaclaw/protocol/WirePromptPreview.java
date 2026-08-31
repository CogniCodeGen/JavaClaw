package com.javaclaw.protocol;

import java.util.List;

/**
 * Profile 编辑层与内置模板的组成；预览不调用模型，不包含凭据或完整私密上下文。
 *
 * @param profileId 被预览的 Profile 标识
 * @param revision 当前 Profile 版本，后续保存必须再次检查
 * @param editablePrompt 用户原稿，仅控制人设、流程与回复风格
 * @param purpose 由 Profile 模式解析的调用用途
 * @param layers 不可直接编辑的内置模板有序版本
 * @param tools 当前已知的工具名称，不等于执行授权
 * @param warnings 能力未验证、不可用或不匹配的提示
 */
public record WirePromptPreview(
        String profileId,
        long revision,
        String editablePrompt,
        String purpose,
        List<Layer> layers,
        List<String> tools,
        List<String> warnings) {
    /** 固定版本层、工具目录与警告的顺序。 */
    public WirePromptPreview {
        layers = List.copyOf(layers);
        tools = List.copyOf(tools);
        warnings = List.copyOf(warnings);
    }

    /**
     * 随代码发布的内置模板引用。
     *
     * @param id 模板稳定标识
     * @param version 正数模板版本
     * @param sha256 模板正文摘要
     */
    public record Layer(String id, int version, String sha256) {}
}
