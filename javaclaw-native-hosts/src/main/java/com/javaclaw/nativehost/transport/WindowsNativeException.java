package com.javaclaw.nativehost.transport;

import java.io.IOException;

/** 保留 Win32 错误码的本机传输异常。 */
final class WindowsNativeException extends IOException {
    private final int errorCode;

    WindowsNativeException(String operation, int errorCode) {
        super(operation + " failed with Win32 error " + Integer.toUnsignedString(errorCode));
        this.errorCode = errorCode;
    }

    int errorCode() {
        return errorCode;
    }
}
