package com.javaclaw.cli;

import java.nio.file.Path;

import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.ManagementDocuments;

/** 插件、MCP、站点和准确授权的 CLI 用例；来源信任、权限与凭据保持独立确认。 */
final class CliExtensionCommands {
    private CliExtensionCommands() {}

    static Object execute(JavaClawClient client, JavaClawCli.Arguments args) throws Exception {
        var extensions = client.extensions();
        String key = args.key();
        return switch (args.command) {
            case "plugin-read" -> extensions.readPlugin(args.required("plugin")).join();
            case "plugin-health" ->
                extensions.pluginHealth(args.required("plugin")).join();
            case "plugin-preview" ->
                extensions.previewPlugin(CliFiles.attachment(client, args)).join();
            case "plugin-install" -> {
                args.confirm();
                if (!args.booleanValue("source-confirmed", false)
                        || !args.booleanValue("permissions-approved", false)) {
                    throw new IllegalArgumentException(
                            "plugin installation requires --source-confirmed true and --permissions-approved true");
                }
                yield extensions
                        .installPlugin(
                                CliFiles.attachment(client, args), true, true, args.booleanValue("enabled", false), key)
                        .join();
            }
            case "plugin-enable", "plugin-disable" ->
                extensions
                        .setPluginEnabled(
                                args.required("plugin"), args.command.equals("plugin-enable"), args.revision(), key)
                        .join();
            case "plugin-uninstall" -> {
                args.confirm();
                yield extensions
                        .uninstallPlugin(args.required("plugin"), args.revision(), key)
                        .join();
            }
            case "plugin-trust-list" -> extensions.listTrustKeys().join();
            case "plugin-trust-add" -> {
                args.confirm();
                yield extensions
                        .addTrustKey(
                                args.required("trust-key"),
                                CliFiles.bytes(Path.of(args.required("file")), 8192),
                                args.required("label"),
                                args.revision(),
                                key)
                        .join();
            }
            case "plugin-trust-remove" -> {
                args.confirm();
                yield extensions
                        .removeTrustKey(args.required("trust-key"), args.revision(), key)
                        .join();
            }
            case "mcp-read" -> extensions.readMcpSettings(args.required("mcp")).join();
            case "mcp-health" -> extensions.mcpHealth(args.required("mcp")).join();
            case "mcp-configure" ->
                extensions
                        .configureMcpServer(
                                args.required("mcp"),
                                args.required("name"),
                                CliFiles.document(args),
                                args.booleanValue("enabled", false),
                                args.revision(),
                                key)
                        .join();
            case "mcp-enable", "mcp-disable" -> {
                var original = extensions.listMcpServers().join().stream()
                        .filter(value -> value.id().equals(args.required("mcp")))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("MCP server does not exist"));
                if (original.revision() != args.revision()) {
                    throw new IllegalArgumentException(
                            "MCP revision changed; reload before changing its enabled state");
                }
                yield extensions
                        .setMcpEnabled(original, args.command.equals("mcp-enable"), key)
                        .join();
            }
            case "mcp-authorize-start" -> {
                args.confirm();
                args.requirePersistent();
                yield extensions.startMcpAuthorization(args.required("mcp")).join();
            }
            case "mcp-authorize-cancel" ->
                extensions
                        .cancelMcpAuthorization(args.required("authorization"))
                        .join();
            case "mcp-credential-read" ->
                extensions.readMcpCredential(args.required("mcp")).join();
            case "mcp-credential-set" ->
                CliFiles.credential(args, value -> extensions.setMcpCredential(args.required("mcp"), value, key));
            case "mcp-credential-clear" -> {
                args.confirm();
                yield extensions
                        .clearMcpCredential(args.required("mcp"), args.revision(), key)
                        .join();
            }
            case "site-list" -> extensions.listSites(args.required("workspace")).join();
            case "site-put" -> {
                args.confirm();
                yield extensions
                        .saveSite(ManagementDocuments.site(CliFiles.document(args)), true, key)
                        .join();
            }
            case "site-delete" -> {
                args.confirm();
                yield extensions
                        .disableSite(args.required("site"), args.revision(), key)
                        .join();
            }
            case "site-credential-read" ->
                extensions
                        .readSiteCredential(args.required("site"), args.required("name"))
                        .join();
            case "site-credential-set" ->
                CliFiles.credential(
                        args,
                        value ->
                                extensions.setSiteCredential(args.required("site"), args.required("name"), value, key));
            case "site-credential-clear" -> {
                args.confirm();
                yield extensions
                        .clearSiteCredential(args.required("site"), args.required("name"), args.revision(), key)
                        .join();
            }
            case "site-login-start" -> {
                args.confirm();
                args.requirePersistent();
                yield extensions
                        .startBrowserLogin(args.required("site"), args.revision(), true, key)
                        .join();
            }
            case "site-login-finish" -> {
                args.required("save");
                yield extensions
                        .finishBrowserLogin(args.required("session"), args.booleanValue("save", false), key)
                        .join();
            }
            case "site-session-read" ->
                extensions.readBrowserSession(args.required("site")).join();
            case "site-session-clear" -> {
                args.confirm();
                yield extensions
                        .clearBrowserSession(args.required("site"), args.revision(), key)
                        .join();
            }
            case "network-grant-list" ->
                extensions.listNetworkGrants(args.required("workspace")).join();
            case "network-grant-put" -> {
                args.confirm();
                yield extensions
                        .saveNetworkGrant(ManagementDocuments.networkGrant(CliFiles.document(args)), true, key)
                        .join();
            }
            case "network-grant-delete" -> {
                args.confirm();
                yield extensions
                        .disableNetworkGrant(args.required("grant"), args.revision(), key)
                        .join();
            }
            case "tool-authorization-options" ->
                extensions.toolAuthorizationOptions(args.required("workspace")).join();
            case "tool-authorization-list" ->
                extensions.listToolAuthorizations(args.required("workspace")).join();
            case "tool-authorization-put" -> {
                args.confirm();
                yield extensions
                        .saveToolAuthorization(
                                ManagementDocuments.toolAuthorization(CliFiles.document(args)), true, key)
                        .join();
            }
            case "tool-authorization-delete" -> {
                args.confirm();
                yield extensions
                        .disableToolAuthorization(args.required("authorization"), args.revision(), key)
                        .join();
            }
            default -> throw new IllegalArgumentException("unknown extension command: " + args.command);
        };
    }
}
