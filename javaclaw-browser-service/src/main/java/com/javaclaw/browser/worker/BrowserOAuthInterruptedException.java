package com.javaclaw.browser.worker;

/** OAuth 窗口被取消或到达硬时限；只公开稳定错误码。 */
final class BrowserOAuthInterruptedException extends RuntimeException {
    private final String errorCode;

    BrowserOAuthInterruptedException(String errorCode) {
        super(errorCode);
        if (!"OAUTH_CANCELLED".equals(errorCode) && !"OAUTH_EXPIRED".equals(errorCode)) {
            throw new IllegalArgumentException("unsupported OAuth interruption code");
        }
        this.errorCode = errorCode;
    }

    String errorCode() {
        return errorCode;
    }
}
