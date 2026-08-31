package com.javaclaw.cli;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.model.BrowserSiteInfo;
import com.javaclaw.sdk.model.NetworkGrantInfo;
import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.ServerInfo;
import com.javaclaw.sdk.model.ThreadInfo;
import com.javaclaw.sdk.model.ToolAuthorizationInfo;
import com.javaclaw.sdk.model.WorkspaceInfo;
import com.javaclaw.server.browser.BrowserSiteService;
import com.javaclaw.server.configuration.ServerConfiguration;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.extension.ToolAuthorityOption;
import com.javaclaw.server.extension.ToolAuthorizationService;
import com.javaclaw.server.model.CloudModelDescriptor;
import com.javaclaw.server.network.NetworkGrantService;
import com.javaclaw.server.persistence.H2BrowserSiteRepository;
import com.javaclaw.server.persistence.H2NetworkGrantRepository;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.persistence.H2ProfileRepository;
import com.javaclaw.server.persistence.H2ToolAuthorizationRepository;
import com.javaclaw.server.transport.AppServerEndpointConfig;
import com.javaclaw.server.transport.ServerUseCases;
import com.javaclaw.server.transport.StdioAppServer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliResourcesIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void cliAndSdkShareRealRevisionProtectedResourcesAndVerifiedAttachmentBytes() throws Exception {
        var codec = new JsonRpcCodec();
        var events = new RuntimeEventBus();
        String schema = """
                {"type":"object","additionalProperties":false,"properties":{"to":{"type":"string"},"body":{"type":"string"}},"required":["to","body"]}
                """.strip();
        try (var persistence = new H2Persistence(temporary.resolve("data"));
                var runtime = new DefaultAgentRuntime(
                        persistence.runtime(),
                        (context, sink) -> {
                            throw new AssertionError("管理命令不能调用模型");
                        },
                        events);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var profiles = new ProfileService(new H2ProfileRepository(persistence.database()), Set.of());
            var network = new NetworkGrantService(
                    new H2NetworkGrantRepository(persistence.database()), persistence.workspaces());
            var authorizations = new ToolAuthorizationService(
                    new H2ToolAuthorizationRepository(persistence.database()),
                    persistence.workspaces(),
                    ignored -> List.of(new ToolAuthorityOption(
                            "mail",
                            "mcp__mail__tool__send",
                            "synthetic mail fixture",
                            1,
                            PromptHashes.sha256(schema),
                            schema)),
                    codec.mapper());
            try (var sites = new BrowserSiteService(
                    new H2BrowserSiteRepository(persistence.database()),
                    persistence.workspaces(),
                    persistence.attachments(),
                    persistence.journal(),
                    persistence.secretStore(temporary.resolve("configuration")),
                    null,
                    network)) {
                ServerDiscovery discovery = new ServerDiscovery() {
                    @Override
                    public List<CloudModelDescriptor> models() {
                        return List.of(new CloudModelDescriptor("openai", "fake", false, "fixture"));
                    }

                    @Override
                    public List<ToolDescriptor> tools() {
                        return List.of(new ToolDescriptor("inspect", "fixture", "{\"type\":\"object\"}"));
                    }
                };
                var endpoint = new AppServerEndpointConfig(
                        runtime,
                        events,
                        codec,
                        StdioAppServer.DEFAULT_MAX_FRAME_CHARS,
                        null,
                        null,
                        discovery,
                        false,
                        ServerConfiguration.inMemory(codec.mapper()),
                        persistence.attachments(),
                        runtime.liveItemEvents(),
                        new ServerUseCases(
                                profiles,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                sites,
                                network,
                                authorizations,
                                null,
                                null));
                var requests = Pipe.open();
                var responses = Pipe.open();
                var serving = executor.submit(() -> {
                    try (var input = Channels.newInputStream(requests.source());
                            var output = Channels.newOutputStream(responses.sink())) {
                        new StdioAppServer(endpoint)
                                .serve(
                                        new InputStreamReader(input, StandardCharsets.UTF_8),
                                        new OutputStreamWriter(output, StandardCharsets.UTF_8));
                    }
                    return null;
                });
                try (var sdk = JavaClawClient.connect(
                        Channels.newInputStream(responses.source()), Channels.newOutputStream(requests.sink()))) {
                    var server = sdk.initialize("cli-resource-test", "1").join();
                    Path root = Files.createDirectory(temporary.resolve("workspace"));
                    var workspace = assertInstanceOf(
                            WorkspaceInfo.class,
                            command(sdk, server, "workspace-create", "--name", "CLI", "--root", root.toString()));
                    assertEquals(
                            workspace, sdk.workspaces().read(workspace.id()).join());
                    var thread = assertInstanceOf(
                            ThreadInfo.class,
                            command(sdk, server, "thread-start", "--workspace", workspace.id(), "--title", "保留原 UI"));
                    assertEquals(thread, sdk.threads().read(thread.id()).join().thread());
                    assertEquals(
                            "openai",
                            sdk.models().listModels().join().getFirst().provider());
                    assertEquals(
                            "{\"type\":\"object\"}",
                            sdk.models()
                                    .listTools()
                                    .join()
                                    .getFirst()
                                    .inputSchema()
                                    .canonicalJson());

                    var profile = assertInstanceOf(
                            ProfileInfo.class,
                            command(sdk, server, "profile-put", "--file", file("profile.json", """
                            {"id":"test","name":"测试","kind":"CHAT","provider":"openai","model":"fake","systemPrompt":"",
                            "enabledTools":[],"requestedSandboxMode":"READ_ONLY","maxIterations":8,"maxModelCalls":8,"attributes":{},"revision":0}
                            """)));
                    assertEquals(profile, sdk.models().readProfile("test").join());
                    var site = assertInstanceOf(
                            BrowserSiteInfo.class,
                            command(
                                    sdk,
                                    server,
                                    "site-put",
                                    "--confirm",
                                    "true",
                                    "--file",
                                    file("site.json", """
                            {"workspaceId":"%s","name":"示例","origin":"https://example.test","allowedOrigins":[],
                            "enabled":true,"revision":0}
                            """.formatted(workspace.id()))));
                    assertEquals(
                            site,
                            sdk.extensions().listSites(workspace.id()).join().getFirst());
                    char[] secret = "synthetic-site-password".toCharArray();
                    try {
                        sdk.extensions()
                                .setSiteCredential(site.id(), "password", secret, "secret")
                                .join();
                    } finally {
                        java.util.Arrays.fill(secret, '\0');
                    }
                    assertTrue(sdk.extensions()
                            .readSiteCredential(site.id(), "password")
                            .join()
                            .isPresent());
                    assertFalse(sdk.extensions()
                            .listSites(workspace.id())
                            .join()
                            .toString()
                            .contains("synthetic-site-password"));

                    var grant = assertInstanceOf(
                            NetworkGrantInfo.class,
                            command(
                                    sdk,
                                    server,
                                    "network-grant-put",
                                    "--confirm",
                                    "true",
                                    "--file",
                                    file("grant.json", """
                            {"workspaceId":"%s","purpose":"BROWSER","origin":"https://intranet.test:8443",
                            "addresses":["10.2.3.4"],"expiresAt":"%s","enabled":true,"revision":0}
                            """.formatted(
                                            workspace.id(), Instant.now().plusSeconds(600)))));
                    assertEquals(
                            grant,
                            sdk.extensions()
                                    .listNetworkGrants(workspace.id())
                                    .join()
                                    .getFirst());
                    var option = sdk.extensions()
                            .toolAuthorizationOptions(workspace.id())
                            .join()
                            .getFirst();
                    String authorizationFile = file("authorization.json", """
                            {"workspaceId":"%s","sourceId":"mail","toolName":"mcp__mail__tool__send","sourceRevision":1,
                            "schemaSha256":"%s","argumentTemplate":{"to":"example@example.test","body":""},
                            "recipientField":"to","variableFields":["body"],"maximumUses":2,"consumedUses":0,
                            "expiresAt":"%s","enabled":true,"revision":0}
                            """.formatted(
                            workspace.id(), option.schemaSha256(), Instant.now().plusSeconds(600)));
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> command(sdk, server, "tool-authorization-put", "--file", authorizationFile));
                    var authorization = assertInstanceOf(
                            ToolAuthorizationInfo.class,
                            command(
                                    sdk,
                                    server,
                                    "tool-authorization-put",
                                    "--file",
                                    authorizationFile,
                                    "--confirm",
                                    "true",
                                    "--idempotency-key",
                                    "same-authorization"));
                    assertEquals(
                            authorization,
                            command(
                                    sdk,
                                    server,
                                    "tool-authorization-put",
                                    "--file",
                                    authorizationFile,
                                    "--confirm",
                                    "true",
                                    "--idempotency-key",
                                    "same-authorization"));
                    assertEquals(
                            1,
                            sdk.extensions()
                                    .listToolAuthorizations(workspace.id())
                                    .join()
                                    .size());

                    byte[] bytes = new byte[2 * 1024 * 1024 + 31];
                    new java.util.Random(7).nextBytes(bytes);
                    Path source = temporary.resolve("source.bin");
                    Files.write(source, bytes);
                    var attachment = sdk.attachments()
                            .upload(source, "application/octet-stream", "attachment")
                            .join();
                    Path downloaded = temporary.resolve("downloaded.bin");
                    command(
                            sdk,
                            server,
                            "attachment-download",
                            "--sha",
                            attachment.sha256(),
                            "--output",
                            downloaded.toString());
                    assertArrayEquals(bytes, Files.readAllBytes(downloaded));
                    assertArrayEquals(
                            bytes,
                            sdk.attachments()
                                    .readContent(attachment.sha256(), bytes.length)
                                    .join()
                                    .bytes());
                    assertThrows(
                            RuntimeException.class,
                            () -> sdk.attachments()
                                    .readContent(attachment.sha256(), bytes.length - 1)
                                    .join());
                    assertThrows(
                            RuntimeException.class,
                            () -> sdk.attachments()
                                    .download(attachment.sha256(), downloaded, false)
                                    .join());
                    assertArrayEquals(bytes, Files.readAllBytes(downloaded));
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> command(
                                    sdk,
                                    server,
                                    "site-login-start",
                                    "--site",
                                    site.id(),
                                    "--revision",
                                    "1",
                                    "--confirm",
                                    "true"));
                    assertEquals(
                            Boolean.TRUE,
                            command(
                                    sdk,
                                    server,
                                    "network-grant-delete",
                                    "--grant",
                                    grant.id(),
                                    "--revision",
                                    "1",
                                    "--confirm",
                                    "true"));
                }
                serving.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private Object command(JavaClawClient sdk, ServerInfo server, String... args) throws Exception {
        return JavaClawCli.executeCommand(sdk, server, JavaClawCli.Arguments.parse(args), System.out, System.err);
    }

    private String file(String name, String content) throws Exception {
        return Files.writeString(temporary.resolve(name), content).toString();
    }
}
