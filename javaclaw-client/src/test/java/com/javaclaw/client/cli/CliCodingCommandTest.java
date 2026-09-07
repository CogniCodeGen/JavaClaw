package com.javaclaw.client.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliCodingCommandTest {
    @Test
    void 编程设置写入要求明确并发版本与稳定幂等键() {
        String workspace = WorkspaceId.random().toString();
        String json = "{\"allowLifecycleScripts\":true,\"name\":\"dev\",\"repositoryHosts\":[],\"toolchains\":[]}";
        var command = CliCodingCommand.parse(List.of("coding", "save", workspace, json, "3", "retry-key"));
        assertEquals(3, command.options().orElseThrow().expectedRevision());
        assertEquals("retry-key", command.options().orElseThrow().idempotencyKey());
        assertThrows(
                IllegalArgumentException.class,
                () -> CliCodingCommand.parse(List.of("coding", "save", workspace, json)));
        assertThrows(
                IllegalArgumentException.class,
                () -> CliCodingCommand.parse(List.of("coding", "dependencies_prepare", workspace)));
        assertThrows(
                IllegalArgumentException.class,
                () -> CliCodingCommand.parse(List.of("coding", "terminal_write", workspace)));
    }

    @Test
    void 目录与Job读取无需客户端声明Turn权限() {
        assertEquals(
                "catalog",
                CliCodingCommand.parse(List.of(
                                "coding", "catalog", WorkspaceId.random().toString()))
                        .action());
        assertEquals(
                "job-1",
                CliCodingCommand.parse(List.of("coding", "job", "job-1")).target());
        assertThrows(
                IllegalArgumentException.class, () -> CliCodingCommand.parse(List.of("coding", "catalog", "invalid")));
    }
}
