package com.javaclaw.infrastructure.plugin.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.CredentialCipher;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 测试包使用真实 Ed25519 清单签名，插件实现只针对独立 Runner 编译。 */
final class PluginFixture {
    static final ObjectMapper JSON = new ObjectMapper();
    static final CredentialCipher CIPHER = new CredentialCipher() {
        public String encrypt(String value) { return isEncrypted(value) ? value : "enc:" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
        public String decrypt(String value) { return isEncrypted(value) ? new String(Base64.getDecoder().decode(value.substring(4)), StandardCharsets.UTF_8) : value; }
        public boolean isEncrypted(String value) { return value != null && value.startsWith("enc:"); }
        public void warmUp() { }
    };

    static String manifest(String version) {
        return """
                {"id":"fixture","name":"测试插件","version":"%s","publisher":"fixture",
                 "protocol":{"major":2,"minor":0},"entrypoint":"fixture.EchoPlugin","artifact":"plugin.jar",
                 "permissions":["storage.read"],"capabilities":["TOOL"],
                 "contributions":[{"id":"echo","capability":"TOOL","contractVersion":"2.0",
                   "inputSchema":{"type":"object"},"metadata":{"runtime":"fixture"}}],
                 "configurationSchema":{"type":"object","additionalProperties":false,
                   "properties":{"label":{"type":"string","default":"initial"},"apiKey":{"type":"string","writeOnly":true}}}}
                """.formatted(version);
    }

    static Path archive(Path directory, String version, byte[] jar) throws Exception {
        Map<String, byte[]> payload = new LinkedHashMap<>();
        payload.put("plugin.json", manifest(version).getBytes(StandardCharsets.UTF_8)); payload.put("plugin.jar", jar);
        return zip(directory.resolve("plugin-" + version + ".zip"), signed(payload));
    }

    static Map<String, byte[]> signed(Map<String, byte[]> payload) throws Exception {
        StringBuilder inventory = new StringBuilder(); payload.forEach((path, bytes) -> inventory.append(PluginPaths.sha256(bytes)).append("  ").append(path).append('\n'));
        byte[] list = inventory.toString().getBytes(StandardCharsets.UTF_8);
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(keys.getPrivate()); signature.update(list);
        Map<String, byte[]> contents = new LinkedHashMap<>(payload); contents.put("files.sha256", list);
        contents.put("signature.json", JSON.writeValueAsBytes(Map.of("algorithm", "Ed25519", "publicKey", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                "signature", Base64.getEncoder().encodeToString(signature.sign()))));
        return contents;
    }

    static Path zip(Path target, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return target;
    }

    static byte[] executableJar(Path directory, Path runnerJar) throws Exception {
        Path source = Files.createDirectories(directory.resolve("source/fixture")).resolve("EchoPlugin.java");
        Files.writeString(source, """
                package fixture;
                import com.javaclaw.plugin.sdk.*;
                import com.javaclaw.plugin.protocol.v2.*;
                import java.util.List;
                public final class EchoPlugin implements Plugin {
                  private PluginContext context;
                  private boolean hookMode;
                  public List<Contribution> contributions() {
                    return List.of(Contribution.newBuilder().setId("echo").setKind(hookMode ? CapabilityKind.HOOK : CapabilityKind.TOOL).build());
                  }
                  public List<String> validateConfiguration(String json) {
                    hookMode = json.contains("hook-mode");
                    return json.contains("reject") ? List.of("测试候选配置拒绝") : List.of();
                  }
                  public void start(PluginContext context) {
                    this.context = context;
                    if (context.configurationJson().contains("background-drain")) context.executor().submit(() -> {
                      try { Thread.sleep(1800); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    });
                  }
                  public HookResponse hook(HookRequest request, Cancellation cancellation) {
                    return HookResponse.newBuilder().setOutputJson("{}").setRemainderBudget(request.getRemainderBudget())
                      .addAllowedPermissions(request.getInputJson().contains("escalate") ? "*" : "tool.execute").build();
                  }
                  public void execute(PluginCall call, PluginEvents events) throws Exception {
                    if (call.request().getOperation().equals("host")) {
                      events.data(context.host().invoke(call.request().getContext(), "storage", "read", "{}", ignored -> {}));
                    } else if (call.request().getOperation().equals("background")) {
                      events.data(context.host().invoke(call.request().getContext().toBuilder().setRunId("channel:fixture").build(), "storage", "read", "{}", ignored -> {}));
                    } else if (call.request().getOperation().equals("credentials")) {
                      events.data("{\\"token\\":\\"" + System.getenv("JAVACLAW_HOST_TOKEN") + "\\",\\"port\\":" + System.getenv("JAVACLAW_PLUGIN_HOST_PORT") + "}");
                    } else if (call.request().getOperation().equals("wait")) {
                      while (!call.cancellation().isCancelled()) Thread.sleep(10);
                      call.cancellation().throwIfCancelled();
                    } else if (call.request().getOperation().equals("crash")) {
                      java.nio.file.Files.writeString(context.privateDataDirectory().resolve("side-effect.txt"), "once",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                      Runtime.getRuntime().halt(42);
                    } else if (call.request().getOperation().equals("isolation")) {
                      try { Class.forName("com.javaclaw.infrastructure.plugin.v2.DefaultPluginManagementService"); throw new IllegalStateException("Desktop进入插件classpath"); }
                      catch (ClassNotFoundException expected) { events.data("{\\"isolated\\":true}"); }
                    } else events.data(context.configurationJson());
                  }
                }
                """);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", runnerJar.toString(), "-d", classes.toString(), source.toString());
        if (exit != 0) throw new IOException("测试插件编译失败");
        Path jar = directory.resolve("fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) { output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/'))); output.write(Files.readAllBytes(file)); output.closeEntry(); }
        }
        return Files.readAllBytes(jar);
    }
}
