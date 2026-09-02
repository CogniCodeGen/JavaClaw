package com.javaclaw.extension.spi;

/** 扩展已禁用、隔离、revision 变化或实时撤权。 */
public final class ExtensionAccessDeniedException extends RuntimeException {
    /**
     * 创建拒绝异常。
     *
     * @param message 面向日志的拒绝原因
     */
    public ExtensionAccessDeniedException(String message) {
        super(message);
    }
}
