package com.javaclaw.application.inference;

import java.util.List;

/** 保存公开 API 配置后原子重载监听器的应用端口。 */
public interface InferenceApiServerControlPort {
    void reconfigure() throws Exception;
    String endpoint();

    /** Synchronizes the complete enabled alias catalog without starting a stopped service. */
    default void syncPublishedModels() throws Exception { }

    /** Applies the privacy-safe invocation log switch without restarting the plugin process. */
    default void setInvocationLogging(boolean enabled) throws Exception { }

    /** Protocol 1.2 introduced the hot logging operation. */
    default boolean supportsInvocationLogging() { return false; }

    default ServiceSnapshot serviceSnapshot() {
        return new ServiceSnapshot(status(), "STOPPED", 0, 0, List.of(),
                supportsInvocationLogging());
    }

    default ApiServerStatus status() {
        String value = endpoint();
        return value == null || value.isBlank()
                ? new ApiServerStatus(State.DISABLED, "", "")
                : new ApiServerStatus(State.AVAILABLE, value, "");
    }

    /** Returns the one canonical OpenAI base URL used by both the UI and callers. */
    static String normalizeOpenAiBaseUrl(String endpoint) {
        String value = endpoint == null ? "" : endpoint.strip();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        while (value.endsWith("/v1/v1")) value = value.substring(0, value.length() - 3);
        return value.isBlank() || value.endsWith("/v1") ? value : value + "/v1";
    }

    enum State { DISABLED, CONFIGURED_STOPPED, AVAILABLE, FAILED }

    record ApiServerStatus(State state, String endpoint, String detail) {
        public ApiServerStatus {
            state = state == null ? State.DISABLED : state;
            endpoint = normalizeOpenAiBaseUrl(endpoint);
            detail = detail == null ? "" : detail.strip();
        }

        public boolean available() { return state == State.AVAILABLE; }
    }

    record ServiceSnapshot(ApiServerStatus api, String processState, long pid,
                           int activeRequests, List<String> recentLogs,
                           boolean invocationLoggingSupported) {
        public ServiceSnapshot {
            api = api == null ? new ApiServerStatus(State.DISABLED, "", "") : api;
            processState = processState == null || processState.isBlank()
                    ? "STOPPED" : processState.strip();
            recentLogs = recentLogs == null ? List.of() : List.copyOf(recentLogs);
        }
    }

    InferenceApiServerControlPort NOOP = new InferenceApiServerControlPort() {
        @Override public void reconfigure() { }
        @Override public String endpoint() { return ""; }
        @Override public ApiServerStatus status() {
            return new ApiServerStatus(State.DISABLED, "", "");
        }
    };
}
