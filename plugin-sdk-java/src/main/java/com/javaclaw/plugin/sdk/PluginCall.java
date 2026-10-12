package com.javaclaw.plugin.sdk;

import com.javaclaw.plugin.protocol.v2.CallRequest;
import com.javaclaw.plugin.protocol.v2.CapabilityKind;
import java.util.Objects;

/** 单次调用使用不可变数据，取消句柄只在本插件进程内生效。 */
public record PluginCall(CapabilityKind kind, CallRequest request, Cancellation cancellation) {
    public PluginCall {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
    }
    public String contributionId() { return request.getContributionId(); }
    public String payloadJson() { return request.getPayloadJson(); }
}
