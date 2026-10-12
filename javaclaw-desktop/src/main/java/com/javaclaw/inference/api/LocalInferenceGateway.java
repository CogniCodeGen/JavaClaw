package com.javaclaw.inference.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** JavaClaw 内部访问本地推理的唯一入口。 */
public interface LocalInferenceGateway {

    /** 将业务调用绑定到 Run 已验证的模型快照；普通设置页继续使用当前实例。 */
    default LocalInferenceGateway forSnapshots(java.util.List<InferenceProviderSnapshot> snapshots) { return this; }

    InferenceChatResponse chat(InferenceChatRequest request) throws InferenceException;

    StreamSession streamChat(InferenceChatRequest request, Consumer<InferenceStreamEvent> events)
            throws InferenceException;

    InferenceEmbeddingResponse embeddings(InferenceEmbeddingRequest request) throws InferenceException;

    boolean cancel(String requestId);

    interface StreamSession extends AutoCloseable {
        String requestId();
        CompletableFuture<InferenceChatResponse> completion();
        boolean cancel();

        @Override
        default void close() { cancel(); }
    }

    class InferenceException extends Exception {
        private final String code;
        private final boolean retryable;
        private final InferenceUsage usage;

        public InferenceException(String code, String message, boolean retryable) {
            this(code, message, retryable, new InferenceUsage(0, 0), null);
        }

        public InferenceException(String code, String message, boolean retryable, Throwable cause) {
            this(code, message, retryable, new InferenceUsage(0, 0), cause);
        }

        public InferenceException(
                String code, String message, boolean retryable, InferenceUsage usage, Throwable cause) {
            super(message, cause);
            this.code = code == null ? "inference_error" : code;
            this.retryable = retryable;
            this.usage = usage == null ? new InferenceUsage(0, 0) : usage;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public InferenceUsage usage() { return usage; }
    }
}
