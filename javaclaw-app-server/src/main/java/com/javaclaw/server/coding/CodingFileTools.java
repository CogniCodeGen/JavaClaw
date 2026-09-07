package com.javaclaw.server.coding;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.protocol.CanonicalJson;

/** 将 Coding 文件查询契约转换到固定原生 Worker；不在宿主直接打开项目文件。 */
final class CodingFileTools {
    private final CanonicalJson json;
    private final CodingPatchExecutor patches;

    CodingFileTools(CanonicalJson json, CodingPatchExecutor patches) {
        this.json = json;
        this.patches = patches;
    }

    CodingToolResult execute(String operation, CodingInvocation invocation) throws Exception {
        WorkspaceFileAccess files = new WorkspaceFileAccess(invocation.turn().executionRoot(), invocation.permission());
        return switch (operation) {
            case "file_list" -> list(files, invocation);
            case "file_read" -> read(files, invocation);
            case "file_search" -> search(files, invocation);
            case "file_apply_patch" -> patches.apply(files, invocation);
            default -> throw new IllegalArgumentException("未知文件操作");
        };
    }

    private CodingToolResult list(WorkspaceFileAccess files, CodingInvocation invocation) throws Exception {
        var input = json.decode(invocation.request().arguments(), CodingContracts.FileList.class);
        var page = files.list(input.path(), input.afterName(), input.maxEntries(), invocation.cancellation());
        List<CodingResults.FileEntry> entries = page.entries().stream()
                .map(entry -> new CodingResults.FileEntry(
                        entry.path(),
                        entry.directory() ? CodingResults.EntryKind.DIRECTORY : CodingResults.EntryKind.FILE,
                        entry.size()))
                .toList();
        return CodingToolResult.value(new CodingResults.FileListResult(entries, page.nextName()));
    }

    private CodingToolResult read(WorkspaceFileAccess files, CodingInvocation invocation) throws Exception {
        var input = json.decode(invocation.request().arguments(), CodingContracts.FileRead.class);
        var page = files.read(
                input.path(), input.offsetBytes(), input.maxBytes(), 64 * 1024 * 1024, invocation.cancellation());
        byte[] bytes = page.content();
        var window = CodingUtf8Window.decode(
                bytes, page.offsetBytes(), page.offsetBytes() + bytes.length == page.sizeBytes());
        long next = page.offsetBytes() + window.consumed();
        return CodingToolResult.value(new CodingResults.FileReadResult(
                page.path(),
                window.text(),
                page.sha256(),
                page.offsetBytes() + window.start(),
                next,
                next < page.sizeBytes(),
                window.binary()));
    }

    private CodingToolResult search(WorkspaceFileAccess files, CodingInvocation invocation) throws Exception {
        var input = json.decode(invocation.request().arguments(), CodingContracts.FileSearch.class);
        var page = files.searchPage(
                input.path(),
                input.query(),
                input.glob(),
                input.caseSensitive(),
                input.maxMatches(),
                input.maxBytes(),
                invocation.cancellation());
        var matches = page.matches().stream()
                .map(match -> new CodingResults.FileMatch(match.path(), match.line(), match.text()))
                .toList();
        return CodingToolResult.value(
                new CodingResults.FileSearchResult(matches, page.truncated(), page.scannedBytes()));
    }

    static String utf8(byte[] bytes) throws CharacterCodingException {
        if (containsZero(bytes)) {
            throw new CharacterCodingException();
        }
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static boolean containsZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }
}
