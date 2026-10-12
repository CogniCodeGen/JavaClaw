package com.javaclaw.infrastructure.settings;

import com.javaclaw.application.settings.EmbeddingRuntimeProbePort;
import com.javaclaw.memory.embed.EmbeddingGateway;
import com.javaclaw.memory.embed.EmbeddingHealthStatus;

import java.util.Objects;

/** 将工作区唯一 EmbeddingGateway 适配为设置用例可测试的探测端口。 */
public final class EmbeddingGatewayRuntimeProbeAdapter implements EmbeddingRuntimeProbePort {

    private final EmbeddingGateway gateway;

    public EmbeddingGatewayRuntimeProbeAdapter(EmbeddingGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public boolean isReady() {
        return gateway.isModelReady();
    }

    @Override
    public Result probe() {
        var health = gateway.probe();
        boolean healthy = health.status() == EmbeddingHealthStatus.HEALTHY;
        return new Result(healthy, gateway.dimensions(), health.lastError());
    }
}
