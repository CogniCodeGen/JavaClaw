package com.javaclaw.browser.worker;

/** 人工登录被用户取消或到达硬时限；只公开稳定错误码。 */
final class BrowserLoginInterruptedException extends RuntimeException {
    private final String errorCode;

    BrowserLoginInterruptedException(String errorCode) {
        super(errorCode);
        if (!"LOGIN_CANCELLED".equals(errorCode) && !"LOGIN_EXPIRED".equals(errorCode)) {
            throw new IllegalArgumentException("unsupported login interruption code");
        }
        this.errorCode = errorCode;
    }

    String errorCode() {
        return errorCode;
    }
}
