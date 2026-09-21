package com.javaclaw.model;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.UnaryOperator;

import com.openai.client.OpenAIClientAsync;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.services.async.ChatServiceAsync;
import com.openai.services.async.chat.ChatCompletionServiceAsync;

/**
 * 在 Spring AI 接收工具续片前规范化流；保留官方 SDK 的请求、鉴权、重试和连接所有权。
 *
 * <p>只装饰三个公开 SDK 接口及其 withOptions 返回值，不反射私有实现，也不访问原始 HTTP 数据。 每次 createStreaming 都拥有独立合并状态；其他端点和非流式调用直接委托。
 */
final class OpenAiStreamingClient {
    private OpenAiStreamingClient() {}

    static OpenAIClientAsync wrap(OpenAIClientAsync client) {
        return forward(OpenAIClientAsync.class, client, result -> {
            if (result instanceof ChatServiceAsync chat) {
                return chat(chat);
            }
            return result instanceof OpenAIClientAsync changed ? wrap(changed) : result;
        });
    }

    private static ChatServiceAsync chat(ChatServiceAsync service) {
        return forward(ChatServiceAsync.class, service, result -> {
            if (result instanceof ChatCompletionServiceAsync completions) {
                return completions(completions);
            }
            return result instanceof ChatServiceAsync changed ? chat(changed) : result;
        });
    }

    private static ChatCompletionServiceAsync completions(ChatCompletionServiceAsync service) {
        return forward(ChatCompletionServiceAsync.class, service, result -> {
            if (result instanceof AsyncStreamResponse<?> response) {
                return new OpenAiToolStreamResponse(response);
            }
            return result instanceof ChatCompletionServiceAsync changed ? completions(changed) : result;
        });
    }

    private static <T> T forward(Class<T> contract, T delegate, UnaryOperator<Object> results) {
        Object proxy =
                Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract}, (self, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> self == args[0];
                            case "hashCode" -> System.identityHashCode(self);
                            case "toString" -> "OpenAI streaming compatibility: " + contract.getSimpleName();
                            default -> throw new IllegalStateException("Unsupported Object method");
                        };
                    }
                    try {
                        return results.apply(method.invoke(delegate, args));
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        return contract.cast(proxy);
    }
}
