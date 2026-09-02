package com.javaclaw.desktop;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.client.sdk.JavaClawClient;

/** Desktop 输入卡只通过该窄门面读取当前 SDK 会话并提交用户意图。 */
public final class DesktopInputActions {
    private final DesktopInputCoordinator coordinator;
    private final LongSupplier connectionEpoch;
    private final Supplier<JavaClawClient> client;

    DesktopInputActions(
            DesktopInputCoordinator coordinator, LongSupplier connectionEpoch, Supplier<JavaClawClient> client) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.connectionEpoch = Objects.requireNonNull(connectionEpoch, "connectionEpoch");
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 异步提交符合请求 Schema 的结构化输入。
     *
     * @param request 当前 pending revision
     * @param response 输入卡生成的规范对象
     * @return 在 UI 调度器上完成的权威终态记录
     */
    public CompletableFuture<InputRequestRecord> resolve(InputRequestRecord request, CanonicalPayload response) {
        return coordinator.resolve(connectionEpoch.getAsLong(), client.get(), request, response);
    }

    /**
     * 异步取消请求所属 Turn；服务端负责把 InputRequest 一并收口为 CANCELLED。
     *
     * @param request 当前 pending revision
     * @return 在 UI 调度器上完成的 Turn 终态
     */
    public CompletableFuture<AgentTurn> cancel(InputRequestRecord request) {
        return coordinator.cancel(connectionEpoch.getAsLong(), client.get(), request);
    }
}
