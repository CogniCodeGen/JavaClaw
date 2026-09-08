package com.javaclaw.server.preview;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewSourceBoundaryTest extends PreviewServiceFixture {
    @Test
    void 服务关闭后所有来源入口均在读取权威状态之前拒绝() throws Exception {
        var session = previews.openSession();
        previews.close();
        assertThrows(IllegalStateException.class, previews::openSession);
        assertThrows(IllegalStateException.class, () -> session.notifications(ignored -> {}));
        assertThrows(IllegalStateException.class, () -> previews.resolve("first", null, new CancellationSource()));
        assertThrows(
                IllegalStateException.class,
                () -> previews.resource("first", "absent", "file.md", new CancellationSource()));
        assertThrows(IllegalStateException.class, () -> previews.read("first", "absent", 0, 1));
        assertThrows(IllegalStateException.class, () -> previews.renew("first", "absent"));
        session.close();
    }

    @Test
    void 失败结果非Coding来源和无文件语义的工具均不能授权预览() throws Exception {
        var permission = permission(1, List.of(workspace.root()));
        profiles.instantiatePreset(identity("permissionProfile/preset/instantiate", permission), permission);
        Files.writeString(workspace.root().resolve("data.txt"), "value");
        var source = message(permission, "来源", List.of());
        var output = new CodingResults.FileReadResult("data.txt", "value", "a".repeat(64), 0, 5, false, false);
        var failed = toolResult(source, CodingContracts.EXTENSION_ID, "file_read", false, output);
        var foreign = toolResult(source, "example.foreign", "file_read", true, output);
        var unsupported = toolResult(source, CodingContracts.EXTENSION_ID, "shell", true, output);
        for (var result : List.of(failed, foreign, unsupported)) {
            assertThrows(
                    SecurityException.class,
                    () -> previews.resolve(
                            "first",
                            DocumentReference.file(workspace.id(), result.id(), "file:0"),
                            new CancellationSource()));
        }
    }

    @Test
    void 类型化选择器拒绝目录与超出文件结果范围的索引() throws Exception {
        var permission = permission(1, List.of(workspace.root()));
        profiles.instantiatePreset(identity("permissionProfile/preset/instantiate", permission), permission);
        var source = message(permission, "来源", List.of());
        var directory = toolResult(
                source,
                CodingContracts.EXTENSION_ID,
                "file_list",
                true,
                new CodingResults.FileListResult(
                        List.of(new CodingResults.FileEntry("folder", CodingResults.EntryKind.DIRECTORY, 0)),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.file(workspace.id(), directory.id(), "file:0"),
                        new CancellationSource()));
        var file = toolResult(
                source,
                CodingContracts.EXTENSION_ID,
                "file_read",
                true,
                new CodingResults.FileReadResult("data.txt", "", "a".repeat(64), 0, 0, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.file(workspace.id(), file.id(), "file:1"),
                        new CancellationSource()));
    }

    private ItemEnvelope toolResult(ItemEnvelope source, String producer, String tool, boolean success, Object output) {
        String callId = UUID.randomUUID().toString();
        append(
                source,
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall(callId, producer, tool, 1, json.encode(Map.of())));
        return append(
                source,
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(callId, success, json.encode(output), Optional.empty()));
    }
}
