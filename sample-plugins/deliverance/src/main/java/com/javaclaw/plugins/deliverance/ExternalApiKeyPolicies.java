package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.service.api.DesktopServiceClient;
import com.javaclaw.service.api.ExternalInvocation;
import com.javaclaw.service.api.PluginConfig;
import com.javaclaw.service.api.PluginLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-key scopes and quotas enforced inside the isolated endpoint process. */
final class ExternalApiKeyPolicies {
    private final Map<String, PolicyState> policies;
    private final DesktopServiceClient desktop;
    private final ObjectMapper json;
    private final PluginLogger log;

    ExternalApiKeyPolicies(PluginConfig config, DesktopServiceClient desktop,
                           ObjectMapper json, PluginLogger log) {
        this.desktop = desktop;
        this.json = json;
        this.log = log;
        this.policies = parse(config.get("external.apiKeyPolicies"));
        if (policies.isEmpty()) throw new IllegalStateException("external API has no authorized key policy");
    }

    Lease authorize(ExternalInvocation invocation, String scope, String alias) {
        PolicyState state = policies.get(invocation.credentialId());
        if (state == null) throw new PolicyFailure(401, "invalid_api_key", "API Key 策略不存在", null);
        Policy policy = state.policy;
        if (!policy.scopes.contains(scope)
                || (alias != null && !policy.modelAliases.isEmpty()
                && !policy.modelAliases.contains(alias))) {
            throw new PolicyFailure(403, "insufficient_permissions",
                    "API Key 无权执行此操作或访问该模型", alias == null ? null : "model");
        }
        if (!state.concurrent.tryAcquire()) {
            throw new PolicyFailure(429, "concurrency_limit_exceeded", "API Key 并发上限已达到", null);
        }
        long minute = Instant.now().getEpochSecond() / 60;
        RateWindow window = state.windows.computeIfAbsent(minute, ignored -> new RateWindow());
        synchronized (window) {
            if (window.requests + 1 > policy.requestsPerMinute
                    || window.tokens >= policy.tokensPerMinute) {
                state.concurrent.release();
                throw new PolicyFailure(429, "rate_limit_exceeded", "API Key 的 RPM 或 TPM 已达到上限", null);
            }
            window.requests++;
        }
        state.windows.keySet().removeIf(value -> value < minute - 2);
        return new Lease(state, minute, window);
    }

    boolean visible(String credentialId, String alias) {
        PolicyState state = policies.get(credentialId);
        return state != null && state.policy.scopes.contains("MODELS_READ")
                && (state.policy.modelAliases.isEmpty() || state.policy.modelAliases.contains(alias));
    }

    private Map<String, PolicyState> parse(String encoded) {
        try {
            JsonNode root = json.readTree(encoded == null || encoded.isBlank() ? "[]" : encoded);
            if (!root.isArray()) throw new IllegalArgumentException("API Key 策略必须是数组");
            Map<String, PolicyState> result = new LinkedHashMap<>();
            for (JsonNode node : root) {
                Policy policy = new Policy(node.path("keyId").asText(), node.path("prefix").asText(),
                        strings(node.path("scopes")), strings(node.path("modelAliases")),
                        node.path("requestsPerMinute").asInt(),
                        node.path("tokensPerMinute").asLong(), node.path("maxConcurrent").asInt());
                PolicyState state = new PolicyState(policy);
                long initialMinute = node.path("initialMinute").asLong(-1);
                if (initialMinute >= 0) {
                    state.windows.put(initialMinute, new RateWindow(
                            node.path("initialRequests").asLong(0),
                            node.path("initialTokens").asLong(0)));
                }
                if (result.putIfAbsent(policy.prefix, state) != null) {
                    throw new IllegalArgumentException("API Key 前缀重复");
                }
            }
            return Map.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalArgumentException("API Key 策略无效", failure);
        }
    }

    private static Set<String> strings(JsonNode node) {
        if (!node.isArray()) return Set.of();
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) values.add(value.asText());
        });
        return Set.copyOf(values);
    }

    final class Lease implements AutoCloseable {
        private final PolicyState state;
        private final long minute;
        private final RateWindow window;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Protocol.Usage usage = new Protocol.Usage(0, 0);
        private boolean failed = true;

        private Lease(PolicyState state, long minute, RateWindow window) {
            this.state = state;
            this.minute = minute;
            this.window = window;
        }

        void success(Protocol.Usage value) {
            usage = value == null ? new Protocol.Usage(0, 0) : value;
            failed = false;
        }

        void failure(Protocol.Usage value) {
            usage = value == null ? new Protocol.Usage(0, 0) : value;
            failed = true;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (window) {
                window.tokens = safeAdd(window.tokens,
                        safeAdd(usage.promptTokens(), usage.completionTokens()));
            }
            state.concurrent.release();
            report(state.policy.keyId, minute, usage, failed);
        }
    }

    private void report(String keyId, long minute, Protocol.Usage usage, boolean failed) {
        try {
            byte[] body = json.writeValueAsBytes(Map.of(
                    "keyId", keyId,
                    "minute", minute,
                    "promptTokens", usage.promptTokens(),
                    "completionTokens", usage.completionTokens(),
                    "failed", failed));
            DesktopServiceClient.Call call = desktop.invoke("inference/api-usage", "record",
                    "application/json", body, Duration.ofSeconds(3), ignored -> { });
            call.completion().get(3, TimeUnit.SECONDS);
        } catch (Exception failure) {
            log.warn("failed to persist external API usage: "
                    + (failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage()));
        }
    }

    private static long safeAdd(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    static final class PolicyFailure extends RuntimeException {
        final int status;
        final String code;
        final String param;
        PolicyFailure(int status, String code, String message, String param) {
            super(message); this.status = status; this.code = code; this.param = param;
        }
    }

    private record Policy(String keyId, String prefix, Set<String> scopes,
                          Set<String> modelAliases, int requestsPerMinute,
                          long tokensPerMinute, int maxConcurrent) {
        private Policy {
            if (keyId == null || keyId.isBlank() || prefix == null || prefix.isBlank()) {
                throw new IllegalArgumentException("API Key 标识不能为空");
            }
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
            modelAliases = modelAliases == null ? Set.of() : Set.copyOf(modelAliases);
            if (requestsPerMinute < 1 || tokensPerMinute < 1 || maxConcurrent < 1) {
                throw new IllegalArgumentException("API Key 限额无效");
            }
        }
    }

    private static final class PolicyState {
        private final Policy policy;
        private final Semaphore concurrent;
        private final ConcurrentHashMap<Long, RateWindow> windows = new ConcurrentHashMap<>();
        private PolicyState(Policy policy) {
            this.policy = policy;
            this.concurrent = new Semaphore(policy.maxConcurrent, true);
        }
    }

    private static final class RateWindow {
        private long requests;
        private long tokens;
        private RateWindow() { }
        private RateWindow(long requests, long tokens) {
            if (requests < 0 || tokens < 0) throw new IllegalArgumentException("API 用量不能为负数");
            this.requests = requests;
            this.tokens = tokens;
        }
    }
}
