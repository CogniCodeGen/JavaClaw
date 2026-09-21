package com.javaclaw.server.rpc;

import java.util.Optional;
import java.util.Set;

import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 只有已提交登记才使网站列表失效；事件以新网站为资源，避免多个 revision 1 的新网站互相去重。 */
final class SiteRegistrationEvents {
    private static final Set<String> COMMANDS =
            Set.of("registration.begin", "registration.origin", "registration.complete", "registration.cancel");

    private SiteRegistrationEvents() {}

    static boolean handles(ExtensionRpcContracts.CallPayload call) {
        return BuiltinExtensionIds.SITE.equals(call.extensionId()) && COMMANDS.contains(call.operation());
    }

    static Optional<ExtensionRpcContracts.ExtensionEvent> completed(
            ExtensionRpcContracts.CallPayload call, ExtensionResponse response, CanonicalJson json) {
        var session = json.decode(response.payload(), SiteRegistrationContracts.Session.class);
        return session.completed()
                .map(completed -> new ExtensionRpcContracts.ExtensionEvent(
                        call.workspaceId(),
                        call.extensionId(),
                        "site",
                        completed.siteId(),
                        "registration.complete",
                        1));
    }
}
