package com.javaclaw.platform.fxml;

import java.io.IOException;

/** Raised when a running JVM observes classes/resources replaced by a newer local build. */
public final class StaleApplicationBuildException extends IOException {

    public static final String USER_MESSAGE =
            "应用文件已在本次启动后更新，当前窗口仍在运行旧代码。请完整退出 JavaClaw 后重新启动。";

    public StaleApplicationBuildException(Throwable originalFailure) {
        super(USER_MESSAGE);
        if (originalFailure != null) addSuppressed(originalFailure);
    }
}
