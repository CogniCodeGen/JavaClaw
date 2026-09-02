package com.javaclaw.desktop.settings;

import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.protocol.ProtocolErrorCode;

/** 设置 Presenter 共用的安全错误投影；不展示 payload、路径或 Secret。 */
final class SettingsFailures {
    private SettingsFailures() {}

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static boolean revisionConflict(Throwable failure) {
        Throwable current = unwrap(failure);
        return current instanceof RemoteRpcException remote && remote.code() == ProtocolErrorCode.REVISION_CONFLICT;
    }

    static String message(Throwable failure) {
        Throwable current = unwrap(failure);
        String message = Optional.ofNullable(current.getMessage())
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .orElse(current.getClass().getSimpleName());
        return message.length() > 400 ? message.substring(0, 400) : message;
    }
}
