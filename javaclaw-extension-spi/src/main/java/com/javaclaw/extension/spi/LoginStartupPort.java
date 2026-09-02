package com.javaclaw.extension.spi;

import java.util.Objects;
import java.util.Optional;

/** 平台用户登录启动项的声明式开关。 */
@FunctionalInterface
public interface LoginStartupPort {
    /**
     * 使登录启动项与是否存在启用 Schedule 保持一致。
     *
     * @param required 至少一个 Schedule 启用时为 true
     */
    void setRequired(boolean required);

    /**
     * 读取不含路径的启动项状态。
     *
     * <p>自定义测试端口默认支持修复但不声明已经安装；原生实现必须覆盖此方法并检查真实平台投影。
     *
     * @param required 权威状态是否要求登录启动
     * @return 脱敏状态
     */
    default Status status(boolean required) {
        return new Status(true, false, Optional.empty());
    }

    /**
     * 登录启动项脱敏状态。
     *
     * @param repairAvailable 当前运行环境能否执行修复
     * @param installed 系统投影是否存在
     * @param unavailableReason 不可修复时的脱敏原因
     */
    record Status(boolean repairAvailable, boolean installed, Optional<String> unavailableReason) {
        /** 校验可用性与原因一致。 */
        public Status {
            unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason")
                    .map(value -> {
                        String normalized = value.strip();
                        if (normalized.isEmpty()) {
                            throw new IllegalArgumentException("unavailableReason must not be blank");
                        }
                        return normalized;
                    });
            if (repairAvailable && unavailableReason.isPresent()) {
                throw new IllegalArgumentException("available startup repair must not have an unavailable reason");
            }
            if (!repairAvailable && unavailableReason.isEmpty()) {
                throw new IllegalArgumentException("unavailable startup repair requires a reason");
            }
        }
    }
}
