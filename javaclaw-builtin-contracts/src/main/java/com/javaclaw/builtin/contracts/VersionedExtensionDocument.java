package com.javaclaw.builtin.contracts;

/** 内置扩展托管文档的共同乐观锁身份。 */
public interface VersionedExtensionDocument {
    /**
     * 返回扩展内稳定标识。
     *
     * @return 文档标识
     */
    String id();

    /**
     * 返回单调 revision。
     *
     * @return 从 1 开始的版本
     */
    long revision();
}
