package com.javaclaw.model;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelInvocation;

/** 已构造且资源所有权明确的 Spring AI 端点。 */
record SpringAiEndpoint(
        String id, ChatModel model, ModelCapabilities capabilities, OptionsFactory options, AutoCloseable resources) {
    SpringAiEndpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(resources, "resources");
    }

    /** 为每次调用创建不可变 Provider options。 */
    @FunctionalInterface
    interface OptionsFactory {
        ChatOptions create(ModelInvocation invocation);
    }
}
