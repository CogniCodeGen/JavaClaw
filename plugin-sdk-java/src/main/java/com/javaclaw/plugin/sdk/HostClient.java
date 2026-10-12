package com.javaclaw.plugin.sdk;

import com.javaclaw.plugin.protocol.v2.CallContext;
import java.util.function.Consumer;

/** 受限宿主能力客户端；服务和操作必须由宿主登记并授权。 */
@FunctionalInterface
public interface HostClient {
    String invoke(CallContext context, String serviceId, String operation,
                  String payloadJson, Consumer<String> events) throws Exception;
}
