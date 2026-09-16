package com.javaclaw.server;

import java.util.Map;

import com.javaclaw.server.rpc.RpcRouter;

/** 现有扩展密封通道的组合注册；秘密入口仍要求当前扩展和 Workspace 有效。 */
final class AppServerSecretBootstrap {
    private AppServerSecretBootstrap() {}

    static void register(RpcRouter.Builder routes, AppServerBootstrap.Foundation foundation) {
        new com.javaclaw.server.rpc.ExtensionSecretRpcHandlers(
                        Map.of(
                                com.javaclaw.builtin.contracts.BuiltinExtensionIds.SITE + "/account/credential/set",
                                new com.javaclaw.server.rpc.SiteAccountSecretHandler(
                                        foundation.siteAccounts(), foundation.json(), call -> {
                                            foundation
                                                    .extensionCatalog()
                                                    .requireEnabled(new com.javaclaw.extension.spi.ExtensionId(
                                                            call.extensionId()));
                                            foundation
                                                    .core()
                                                    .findWorkspace(call.workspaceId())
                                                    .orElseThrow(() -> new SecurityException("Workspace 不存在"));
                                        })),
                        foundation.json())
                .register(routes);
    }
}
