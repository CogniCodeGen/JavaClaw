package com.javaclaw.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * Protocol v2 方法目录条目。
 *
 * @param name 方法名
 * @param kind 副作用类别
 * @param capability 所需能力；基础方法为空
 * @param experimental 是否实验性
 */
public record RpcMethod(String name, RpcMethodKind kind, Optional<String> capability, boolean experimental) {
    /** 校验方法描述。 */
    public RpcMethod {
        name = RpcNames.require(name);
        Objects.requireNonNull(kind, "kind");
        capability = Objects.requireNonNull(capability, "capability")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        if (experimental && capability.isEmpty()) {
            throw new IllegalArgumentException("experimental method requires a capability");
        }
    }
}
