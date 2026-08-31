package com.javaclaw.agent.model;

/** Provider 对给定配置采用的压缩能力；UNKNOWN 不得猜测为原生。 */
public enum CompactionStrategy {
    NATIVE,
    SUMMARY
}
