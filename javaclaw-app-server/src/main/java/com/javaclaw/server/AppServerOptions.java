package com.javaclaw.server;

import java.nio.file.Path;
import java.util.Objects;

import com.javaclaw.nativehost.transport.WindowsPipeName;

/** App Server 本地传输与启动配置。 */
record AppServerOptions(Mode mode, StartupProfile profile, Path socketPath, WindowsPipeName pipeName) {
    AppServerOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(profile, "profile");
        if (mode == Mode.UNIX_SOCKET) {
            Objects.requireNonNull(socketPath, "socketPath");
        } else if (socketPath != null) {
            throw new IllegalArgumentException("only Unix Socket mode may define a socket path");
        }
        if (mode == Mode.NAMED_PIPE) {
            Objects.requireNonNull(pipeName, "pipeName");
        } else if (pipeName != null) {
            throw new IllegalArgumentException("only Named Pipe mode may define a pipe name");
        }
        if (profile == StartupProfile.HEALTH_CHECK && mode != Mode.STDIO) {
            throw new IllegalArgumentException("health-check profile only supports stdio");
        }
    }

    static AppServerOptions parse(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        if (normalStdio(arguments)) {
            return new AppServerOptions(Mode.STDIO, StartupProfile.NORMAL, null, null);
        }
        if (arguments.length == 1 && "--health-check".equals(arguments[0])) {
            return new AppServerOptions(Mode.STDIO, StartupProfile.HEALTH_CHECK, null, null);
        }
        return parseNormalTransport(arguments);
    }

    private static boolean normalStdio(String[] arguments) {
        return arguments.length == 0 || arguments.length == 1 && "--stdio".equals(arguments[0]);
    }

    private static AppServerOptions parseNormalTransport(String[] arguments) {
        if (arguments.length == 2 && "--socket".equals(arguments[0])) {
            Path path = Path.of(arguments[1]);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("--socket requires an absolute path");
            }
            return new AppServerOptions(Mode.UNIX_SOCKET, StartupProfile.NORMAL, path.normalize(), null);
        }
        if (arguments.length == 2 && "--pipe".equals(arguments[0])) {
            return new AppServerOptions(
                    Mode.NAMED_PIPE, StartupProfile.NORMAL, null, WindowsPipeName.parse(arguments[1]));
        }
        if (arguments.length == 1 && "--pipe-default".equals(arguments[0])) {
            return new AppServerOptions(
                    Mode.NAMED_PIPE, StartupProfile.NORMAL, null, WindowsPipeName.currentUserDefault());
        }
        throw new IllegalArgumentException(
                "usage: AppServerMain [--stdio | --health-check | --socket <absolute-path> | --pipe <name> | "
                        + "--pipe-default]");
    }

    enum StartupProfile {
        NORMAL,
        HEALTH_CHECK
    }

    enum Mode {
        STDIO,
        UNIX_SOCKET,
        NAMED_PIPE
    }
}
