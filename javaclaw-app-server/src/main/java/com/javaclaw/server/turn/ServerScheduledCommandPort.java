package com.javaclaw.server.turn;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ScheduledCommand;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.server.extension.BuiltinExtensionHost;

/** 将 Schedule 固定目标路由到显式 SchedulableAction，并保留原 command 幂等与 revision 语义。 */
public final class ServerScheduledCommandPort implements ScheduledCommandPort {
    private final BuiltinExtensionHost extensions;

    /**
     * 创建受限路由。
     *
     * @param extensions 可信内置扩展 Host
     */
    public ServerScheduledCommandPort(BuiltinExtensionHost extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    @Override
    public ExtensionResponse execute(ScheduledCommand command, CancellationToken cancellation) throws Exception {
        ScheduledCommand checked = Objects.requireNonNull(command, "command");
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                checked.extensionId(),
                checked.workspaceId(),
                Optional.empty(),
                Optional.empty(),
                checked.operation(),
                checked.payload());
        return extensions.scheduledCommand(call, checked, cancellation);
    }
}
