package com.javaclaw.extension.spi;

/** 扩展贡献点的共同身份契约。 */
public interface ExtensionContribution {
    /**
     * 返回扩展内唯一标识。
     *
     * @return 稳定标识
     */
    String contributionId();

    /**
     * 返回贡献类别。
     *
     * @return 类别
     */
    ContributionKind kind();
}
