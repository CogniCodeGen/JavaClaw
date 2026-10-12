package com.javaclaw.application.settings;

/** 当前工作区已持久化嵌入模型的健康探测端口。 */
public interface EmbeddingRuntimeProbePort {

    boolean isReady();

    Result probe();

    record Result(boolean healthy, int dimensions, String error) {
        public Result {
            error = error == null ? "" : error.strip();
        }
    }
}
