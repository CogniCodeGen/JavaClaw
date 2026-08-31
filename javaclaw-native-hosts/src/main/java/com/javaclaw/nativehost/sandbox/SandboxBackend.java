package com.javaclaw.nativehost.sandbox;

import java.nio.file.Path;
import java.util.List;

import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxSessionOptions;

interface SandboxBackend {
    String name();

    boolean available();

    List<String> wrap(SandboxCommand command);

    default List<String> wrapSession(SandboxCommand command, SandboxSessionOptions options, Path terminal) {
        if (options.pseudoTerminal()) {
            throw new UnsupportedOperationException("PTY was requested but this backend has no exact implementation");
        }
        return wrap(command);
    }
}
