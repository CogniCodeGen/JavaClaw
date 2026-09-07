package com.javaclaw.nativehost.coding;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.nio.file.Path;

/** 仅由 Native Host 在 OS Sandbox 内启动的固定文件协议入口，不接受可执行代码。 */
public final class WorkspaceFileWorker {
    private WorkspaceFileWorker() {}

    /**
     * 处理一次有界二进制请求后退出；错误只返回分类说明，不输出内容或完整异常栈。
     *
     * @param arguments 唯一参数为服务端冻结的规范 Workspace 根
     * @throws Exception 协议输出管道不可用
     */
    public static void main(String[] arguments) throws Exception {
        System.out.write(process(arguments, System.in));
        System.out.flush();
    }

    /** 固定进程入口和二进制契约测试共用一次请求处理；取得输入流所有权并返回完整有界响应。 */
    static byte[] process(String[] arguments, InputStream source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream response = new DataOutputStream(bytes);
                DataInputStream request = new DataInputStream(source)) {
            try {
                if (arguments.length != 1 || !Path.of(arguments[0]).isAbsolute()) {
                    throw new IllegalArgumentException("Workspace Worker requires one frozen absolute root");
                }
                response.writeBoolean(true);
                try (var tree = new WorkspaceFileTree(Path.of(arguments[0]))) {
                    dispatch(request, response, tree);
                }
            } catch (Exception failure) {
                bytes.reset();
                response.writeBoolean(false);
                String detail =
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                response.writeUTF(detail.length() <= 4096 ? detail : detail.substring(0, 4096));
            }
        }
        return bytes.toByteArray();
    }

    private static void dispatch(DataInputStream request, DataOutputStream response, WorkspaceFileTree tree)
            throws Exception {
        switch (request.readUTF()) {
            case "stat" ->
                WorkspaceFileProtocol.writeEntries(
                        response,
                        WorkspaceFileMetadata.stat(tree, request.readUTF()).stream()
                                .toList());
            case "read-page" -> writeReadPage(request, response, tree);
            case "list-page" -> writeDirectoryPage(request, response, tree);
            case "read" ->
                WorkspaceFileProtocol.writeSnapshot(response, tree.snapshot(request.readUTF(), request.readInt()));
            case "list" ->
                WorkspaceFileProtocol.writeEntries(response, tree.list(request.readUTF(), request.readInt()));
            case "search" -> writeSearchPage(request, response, tree);
            case "inventory" ->
                WorkspaceFileProtocol.writeInventory(
                        response,
                        WorkspaceInventoryScanner.scan(
                                tree,
                                request.readUTF(),
                                request.readInt(),
                                request.readInt(),
                                WorkspaceFileProtocol.readStrings(request, 101)));
            case "prepare" ->
                WorkspaceFileProtocol.writePatch(
                        response,
                        new WorkspacePatchWriter(tree)
                                .prepare(WorkspaceFileProtocol.readEdits(request), request.readInt()));
            case "apply" -> {
                var result = new WorkspacePatchWriter(tree).apply(WorkspaceFileProtocol.readPatch(request));
                response.writeUTF(result.status().name());
                response.writeUTF(result.detail());
                WorkspaceFileProtocol.writeStrings(response, result.recoveryPaths());
            }
            default -> throw new IllegalArgumentException("unknown Workspace file operation");
        }
    }

    private static void writeSearchPage(DataInputStream request, DataOutputStream response, WorkspaceFileTree tree)
            throws Exception {
        var page = tree.searchPage(
                request.readUTF(),
                request.readUTF(),
                request.readUTF(),
                request.readBoolean(),
                request.readInt(),
                request.readInt());
        WorkspaceFileProtocol.writeMatches(response, page.matches());
        response.writeBoolean(page.truncated());
        response.writeLong(page.scannedBytes());
    }

    private static void writeReadPage(DataInputStream request, DataOutputStream response, WorkspaceFileTree tree)
            throws Exception {
        var page = WorkspaceFilePaging.read(
                tree, request.readUTF(), request.readLong(), request.readInt(), request.readInt());
        response.writeUTF(page.path());
        response.writeLong(page.sizeBytes());
        response.writeUTF(page.sha256());
        response.writeLong(page.offsetBytes());
        WorkspaceFileProtocol.writeBytes(response, page.content());
    }

    private static void writeDirectoryPage(DataInputStream request, DataOutputStream response, WorkspaceFileTree tree)
            throws Exception {
        var page = WorkspaceFilePaging.list(tree, request.readUTF(), request.readUTF(), request.readInt());
        WorkspaceFileProtocol.writeEntries(response, page.entries());
        response.writeBoolean(page.nextName().isPresent());
        if (page.nextName().isPresent()) {
            response.writeUTF(page.nextName().orElseThrow());
        }
    }
}
