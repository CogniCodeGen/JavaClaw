package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemPayload;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserResult;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2TurnJournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 从真实 H2 历史读取上传来源，不能用其他 Turn 的调用或其他 Thread 的观察获取文件权限。 */
class BrowserThreadAttachmentsTest {
    @TempDir
    Path directory;

    @Test
    void 跨Turn复用调用ID不能借用旧调用但各Turn完整调用结果可独立使用() {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            AttachmentRef file = file(fixture, 1);
            TurnId earlier = fixture.turn();
            call(fixture, earlier, "same-id", BuiltinExtensionIds.SITE);
            fixture.finish(earlier);
            TurnId current = fixture.turn();
            result(fixture, current, "same-id", output(fixture, fixture.workspace, fixture.thread, file));
            assertRejected(fixture, file);
            // 本 Turn 后到的调用不能给此前结果补授权。
            call(fixture, current, "same-id", BuiltinExtensionIds.SITE);
            assertRejected(fixture, file);
            result(fixture, current, "same-id", output(fixture, fixture.workspace, fixture.thread, file));
            assertEquals(
                    file,
                    new BrowserThreadAttachments(fixture.host)
                            .upload(fixture.workspace, fixture.thread, file.digest()));
        }
    }

    @Test
    void 同Turn重复身份即使在结果之后出现也不能签发上传权限() {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            AttachmentRef file = file(fixture, 2);
            TurnId turn = fixture.turn();
            call(fixture, turn, "duplicate", BuiltinExtensionIds.SITE);
            result(fixture, turn, "duplicate", output(fixture, fixture.workspace, fixture.thread, file));
            call(fixture, turn, "duplicate", "external.extension");
            assertRejected(fixture, file);
        }
    }

    @Test
    void Workspace持有文件也不能通过其他Thread或Workspace观察伪造来源() {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            ThreadId other = fixture.host
                    .core()
                    .createThread(
                            identity("thread/create"),
                            fixture.workspace,
                            Optional.empty(),
                            ThreadExecutionIntent.WORKSPACE,
                            "其他对话")
                    .id();
            TurnId turn = fixture.turn();
            AttachmentRef crossThread = file(fixture, 3);
            call(fixture, turn, "other-thread", BuiltinExtensionIds.SITE);
            result(fixture, turn, "other-thread", output(fixture, fixture.workspace, other, crossThread));
            assertRejected(fixture, crossThread);
            AttachmentRef crossWorkspace = file(fixture, 4);
            call(fixture, turn, "other-workspace", BuiltinExtensionIds.SITE);
            result(
                    fixture,
                    turn,
                    "other-workspace",
                    output(fixture, WorkspaceId.random(), fixture.thread, crossWorkspace));
            assertRejected(fixture, crossWorkspace);
            AttachmentRef valid = file(fixture, 5);
            call(fixture, turn, "valid", BuiltinExtensionIds.SITE);
            result(fixture, turn, "valid", output(fixture, fixture.workspace, fixture.thread, valid));
            assertEquals(
                    valid,
                    new BrowserThreadAttachments(fixture.host)
                            .upload(fixture.workspace, fixture.thread, valid.digest()));
        }
    }

    private static AttachmentRef file(InteractiveBrowserHostFixture fixture, int value) {
        var metadata = fixture.host
                .attachments()
                .store(
                        AttachmentScope.workspace(fixture.workspace),
                        identity("attachment/store"),
                        "text/plain",
                        new byte[] {(byte) value});
        return new AttachmentRef(metadata.digest(), metadata.mediaType(), "文件.txt", metadata.sizeBytes());
    }

    private static void call(InteractiveBrowserHostFixture fixture, TurnId turn, String id, String producer) {
        append(
                fixture,
                turn,
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall(id, producer, "browser_act", 1, fixture.json.parse("{}")));
    }

    private static void result(InteractiveBrowserHostFixture fixture, TurnId turn, String id, BrowserResult result) {
        append(
                fixture,
                turn,
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(id, true, fixture.json.encode(result), Optional.empty()));
    }

    private static void append(InteractiveBrowserHostFixture fixture, TurnId turn, String schema, ItemPayload payload) {
        new H2TurnJournal(
                        fixture.host.database(),
                        CoreItemCodecs.createRegistry(fixture.json),
                        fixture.json,
                        fixture.clock)
                .append(turn, schema, schema, payload, ItemStatus.COMPLETED);
    }

    private static BrowserResult output(
            InteractiveBrowserHostFixture fixture, WorkspaceId workspace, ThreadId thread, AttachmentRef file) {
        var owner = new BrowserContracts.Owner(workspace, thread, Optional.empty());
        var lease = new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.ASSISTANT,
                "lease",
                1,
                fixture.host.clock().instant().plusSeconds(60),
                Set.of(InteractiveBrowserHostFixture.ORIGIN));
        var session = new BrowserContracts.SessionView(
                UUID.randomUUID().toString(), owner, BrowserContracts.SessionState.OPEN, lease, List.of());
        var page = new BrowserContracts.PageSnapshot(
                "page", InteractiveBrowserHostFixture.ORIGIN, "页面", "", List.of(), List.of());
        var artifact = new BrowserContracts.Artifact(
                new BrowserContracts.FileSpec(file.fileName(), file.mediaType()), file.sizeBytes());
        return new BrowserResult(
                1,
                new BrowserContracts.Observation(session, page, Optional.empty(), Optional.of(artifact)),
                Optional.of(file));
    }

    private static void assertRejected(InteractiveBrowserHostFixture fixture, AttachmentRef file) {
        assertThrows(
                SecurityException.class,
                () -> new BrowserThreadAttachments(fixture.host)
                        .upload(fixture.workspace, fixture.thread, file.digest()));
    }

    private static CommandIdentity identity(String operation) {
        return new CommandIdentity(operation, UUID.randomUUID().toString(), 0, "0".repeat(64));
    }
}
