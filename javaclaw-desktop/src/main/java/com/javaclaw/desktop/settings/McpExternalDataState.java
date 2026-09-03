package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpPromptDescriptor;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpResourceDescriptor;
import com.javaclaw.api.McpResourceReadResult;

/** MCP 设置页显式外部数据操作的不可变状态。 */
record McpExternalDataState(
        Optional<McpEndpoint> endpoint,
        SettingsLoadState phase,
        List<McpResourceDescriptor> resources,
        Optional<String> resourceCursor,
        List<McpPromptDescriptor> prompts,
        Optional<String> promptCursor,
        Optional<McpResourceReadResult> resource,
        Optional<McpPromptResult> prompt,
        String message,
        long epoch) {
    McpExternalDataState {
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(phase, "phase");
        resources = List.copyOf(resources);
        resourceCursor = Objects.requireNonNull(resourceCursor, "resourceCursor");
        prompts = List.copyOf(prompts);
        promptCursor = Objects.requireNonNull(promptCursor, "promptCursor");
        resource = Objects.requireNonNull(resource, "resource");
        prompt = Objects.requireNonNull(prompt, "prompt");
        message = Objects.requireNonNull(message, "message");
    }

    static McpExternalDataState initial() {
        return new McpExternalDataState(
                Optional.empty(),
                SettingsLoadState.READY,
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "请选择已启用的 HTTPS 连接",
                0);
    }
}
