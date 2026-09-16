package com.javaclaw.client.extension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.builtin.contracts.CodingSystemContracts;
import com.javaclaw.builtin.contracts.DependencyEvidence;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodingExtensionClientTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.random();
    private static final String EXTENSION = CodingContracts.EXTENSION_ID;

    @Test
    void 环境和工具链使用既有扩展RPC且不声明任意Turn授权() throws IOException {
        var empty = new CodingEnvironmentContracts.Empty();
        var spec = new CodingEnvironmentContracts.EnvironmentSpec("dev", List.of(), Set.of("registry.npmjs.org"), true);
        var current = new CodingEnvironmentContracts.Environment(0, spec);
        var saved = new CodingEnvironmentContracts.Environment(1, spec);
        var ref = new CodingEnvironmentContracts.ToolchainRef(
                CodingEnvironmentContracts.ToolchainKind.NODE, "24", "a".repeat(64));
        var accepted = new CodingEnvironmentContracts.InstallAccepted("job-1", ref.artifactSha256());
        CommandOptions options = new CommandOptions("save", 0);
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        script.expectQuery(EXTENSION, "environment/read", empty, current, 0);
        script.expectQuery(EXTENSION, "toolchain/catalog", empty, new CodingEnvironmentContracts.Catalog(List.of()), 0);
        script.expectQuery(
                EXTENSION, "toolchain/list", empty, new CodingEnvironmentContracts.InstalledList(List.of()), 0);
        script.expectCommand(
                EXTENSION,
                "environment/update",
                new CodingEnvironmentContracts.EnvironmentUpdate(spec),
                options,
                saved,
                1);
        script.expectCommand(
                EXTENSION,
                "toolchain/install",
                new CodingEnvironmentContracts.InstallRequest(ref),
                options,
                accepted,
                0);
        try (RpcClientConnection connection = connection(script)) {
            CodingExtensionClient coding = new BuiltinExtensionClients(new ExtensionClient(connection)).coding();
            assertEquals(current, coding.environment(WORKSPACE));
            assertEquals(List.of(), coding.catalog(WORKSPACE).artifacts());
            assertEquals(List.of(), coding.toolchains(WORKSPACE).toolchains());
            assertEquals(saved, coding.updateEnvironment(WORKSPACE, spec, options));
            assertEquals(accepted, coding.installToolchain(WORKSPACE, ref, options));
        }
        script.assertExhausted();
    }

    @Test
    void 输出游标与资源查询保持typed且更新响应revision必须匹配() throws IOException {
        var read = new CodingResults.OutputRead("resource-1", 32, 64);
        var output = new CodingResults.Output("next", "", 36, false);
        var terminal = new CodingResults.TerminalResult(
                "session-1", CodingResults.ProcessState.RUNNING, output, Optional.empty());
        var spec = new CodingEnvironmentContracts.EnvironmentSpec("dev", List.of(), Set.of(), true);
        CommandOptions options = new CommandOptions("save", 2);
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        script.expectQuery(EXTENSION, "preparation/output", read, output, 0);
        script.expectQuery(EXTENSION, "terminal/output", read, terminal, 0);
        script.expectQuery(EXTENSION, "terminal/read", new CodingResults.ResourceRead("resource-1"), terminal, 0);
        script.expectCommand(
                EXTENSION,
                "environment/update",
                new CodingEnvironmentContracts.EnvironmentUpdate(spec),
                options,
                new CodingEnvironmentContracts.Environment(7, spec),
                3);
        try (RpcClientConnection connection = connection(script)) {
            CodingExtensionClient coding = new CodingExtensionClient(new ExtensionClient(connection));
            assertEquals(output, coding.preparationOutput(WORKSPACE, read));
            assertEquals(terminal, coding.terminalOutput(WORKSPACE, read));
            assertEquals(terminal, coding.terminal(WORKSPACE, "resource-1"));
            assertThrows(IllegalStateException.class, () -> coding.updateEnvironment(WORKSPACE, spec, options));
        }
        script.assertExhausted();
    }

    private static RpcClientConnection connection(ScriptedExtensionConnection script) {
        return new RpcClientConnection(script, new CanonicalJson(), ignored -> {});
    }

    @Test
    void 系统程序登记和文件事实通过管理RPC往返且登记使用乐观版本() throws IOException {
        var registration = new CodingSystemContracts.Registration("custom.echo", "/bin/echo", List.of(), "UTF-8");
        var registry = new CodingSystemContracts.Registry(4, List.of(registration));
        var catalog = new CodingSystemContracts.Catalog("macos-aarch64", 4, List.of());
        var saved = new CodingSystemContracts.Registry(5, List.of());
        var update = new CodingSystemContracts.RegistryUpdate(List.of());
        var filesystem =
                new CodingFileSystemContracts.FileSystemResult("file-1", List.of(), true, Optional.empty(), List.of());
        var options = new CommandOptions("registry-save", 4);
        var script = new ScriptedExtensionConnection(WORKSPACE);
        script.expectQuery(EXTENSION, "system/registry/read", new CodingEnvironmentContracts.Empty(), registry, 4);
        script.expectQuery(EXTENSION, "system/catalog", new CodingEnvironmentContracts.Empty(), catalog, 0);
        script.expectQuery(EXTENSION, "filesystem/result", new CodingResults.ResourceRead("file-1"), filesystem, 0);
        script.expectCommand(EXTENSION, "system/registry/update", update, options, saved, 5);
        try (RpcClientConnection connection = connection(script)) {
            var coding = new CodingExtensionClient(new ExtensionClient(connection));
            assertEquals(registry, coding.systemRegistry(WORKSPACE));
            assertEquals(catalog, coding.systemCatalog(WORKSPACE));
            assertEquals(filesystem, coding.filesystemResult(WORKSPACE, "file-1"));
            assertEquals(saved, coding.updateSystemRegistry(WORKSPACE, update, options));
        }
        script.assertExhausted();
    }

    @Test
    void 独立依赖证据查询不向客户端开放项目命令或Turn授权() throws IOException {
        var inventory = new DependencyEvidence.Inventory(List.of(), 0, false, List.of("budget"));
        var evidence = new DependencyEvidence(
                "prepare-1",
                CodingContracts.PackageManager.NPM,
                inventory,
                Optional.empty(),
                List.of(),
                List.of(),
                false);
        ScriptedExtensionConnection script = new ScriptedExtensionConnection(WORKSPACE);
        script.expectQuery(EXTENSION, "preparation/evidence", new CodingResults.ResourceRead("prepare-1"), evidence, 0);
        try (RpcClientConnection connection = connection(script)) {
            assertEquals(
                    evidence,
                    new CodingExtensionClient(new ExtensionClient(connection))
                            .preparationEvidence(WORKSPACE, "prepare-1"));
        }
        script.assertExhausted();
    }
}
