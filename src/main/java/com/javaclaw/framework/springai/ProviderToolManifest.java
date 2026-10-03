package com.javaclaw.framework.springai;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 当前模型步骤的工具目录；旧步骤的拒绝反馈不能充当下一步的目录。 */
final class ProviderToolManifest {
    static final String METADATA = "javaclaw.currentProviderTools";

    private ProviderToolManifest() { }

    static UserMessage message(List<ToolCallback> callbacks) {
        List<String> names = callbacks.stream()
                .filter(callback -> !(callback instanceof HarnessDecisionToolCallback))
                .map(callback -> callback.getToolDefinition().name()).toList();
        boolean decisionAvailable = callbacks.stream()
                .anyMatch(HarnessDecisionToolCallback.class::isInstance);
        return UserMessage.builder().text("[框架当前步骤工具目录] 本次调用可用工具："
                + (names.isEmpty() ? "无" : String.join("、", names))
                + "。上文 tool_not_offered 反馈中的 offeredTools 只记录失败那一步，"
                + "不能作为当前目录。仅调用本条列出的业务工具，并使用本次工具 Schema 的参数名；"
                + "framework_tool_catalog 未列出时也不可调用。"
                + (decisionAvailable
                    ? "控制通道 harness_submit_decision 始终单独可用；提交时不得与其他工具同批调用，"
                            + "普通回答文字不作为完成标识。"
                    : "")
                + "继续完成原始任务。")
                .metadata(Map.of(METADATA, true)).build();
    }

    static boolean isManifest(Message message) {
        return message instanceof UserMessage user
                && Boolean.TRUE.equals(user.getMetadata().get(METADATA));
    }

    static List<Message> replace(List<Message> messages, List<ToolCallback> callbacks) {
        List<Message> current = new ArrayList<>(messages.stream()
                .filter(message -> !isManifest(message)).toList());
        current.add(message(callbacks));
        return List.copyOf(current);
    }
}
