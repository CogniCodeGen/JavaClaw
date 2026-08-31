package com.javaclaw.agent.tool;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 固定文件 Worker；只能由 Sandbox Supervisor 创建，OS 策略才是子进程的安全边界。 */
public final class FileWorkerMain {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private FileWorkerMain() {}

    /** 从 stdin 接受一条有界请求；不加载插件，不把异常正文或文件内容写入 stderr。 */
    public static void main(String[] args) {
        try {
            byte[] input = System.in.readNBytes(4 * 1024 * 1024 + 1);
            if (input.length > 4 * 1024 * 1024) {
                throw new IllegalArgumentException("request too large");
            }
            JsonNode request = JSON.readTree(input);
            System.out.print(execute(Path.of("").toAbsolutePath().normalize(), request));
        } catch (Throwable failure) {
            System.err.println("file worker failed: " + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    static String execute(Path root, JsonNode request) throws Exception {
        String operation = request.path("operation").asText();
        if (!Set.of("READ", "LIST", "WRITE", "REPLACE").contains(operation)) {
            throw new IllegalArgumentException("unknown file operation");
        }
        String relative = request.path("path").asText();
        if (relative.isBlank()
                || relative.length() > 1_000
                || relative.contains("\\")
                || relative.indexOf('\0') >= 0
                || Path.of(relative).isAbsolute()
                || Path.of(relative).startsWith("..")
                || relative.contains(":")
                || List.of(relative.split("/")).contains("..")) {
            throw new IllegalArgumentException("path must remain relative to workspace");
        }
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace");
        }
        Path current = root;
        for (Path component : root.relativize(path)) {
            if (Set.of(".javaclaw", ".ssh", ".gnupg", ".aws", ".azure", ".kube").contains(component.toString())
                    || (!Set.of("READ", "LIST").contains(operation) && ".git".equals(component.toString()))) {
                throw new IllegalArgumentException("protected directory");
            }
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("symbolic links are not supported by file tools");
            }
        }
        for (var protectedRoot : request.path("protectedRoots")) {
            Path blocked = Path.of(protectedRoot.asText()).toAbsolutePath().normalize();
            if (path.startsWith(blocked) && !".git".equals(blocked.getFileName().toString())) {
                throw new IllegalArgumentException("protected runtime data is not a file-tool input");
            }
        }
        var output = JSON.createObjectNode();
        output.put("path", relative);
        output.put("status", "completed");
        if ("LIST".equals(operation)) {
            var entries = output.putArray("entries");
            try (var stream = Files.list(path)) {
                for (Path child : stream.sorted().limit(1_001).toList()) {
                    if (entries.size() == 1_000) {
                        output.put("truncated", true);
                        break;
                    }
                    entries.add(child.getFileName().toString()
                            + (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) ? "/" : ""));
                }
            }
            return output.toString();
        }
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        byte[] original = exists ? bytes(path) : new byte[0];
        String actual = exists ? digest(original) : "MISSING";
        String text = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(original))
                .toString();
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("binary content requires an attachment tool");
        }
        if ("READ".equals(operation)) {
            if (!exists) {
                throw new IllegalArgumentException("file not found");
            }
            int start = request.path("startLine").asInt(1);
            int count = request.path("lineCount").asInt(200);
            if (start < 1 || count < 1 || count > 500) {
                throw new IllegalArgumentException("invalid line window");
            }
            String selected =
                    text.lines().skip(start - 1L).limit(count).collect(java.util.stream.Collectors.joining("\n"));
            if (selected.length() > 24_000) {
                throw new IllegalArgumentException("selected lines exceed output bound; request fewer lines");
            }
            output.put("sha256", actual);
            output.put("content", selected);
            output.put("startLine", start);
            output.put("totalLines", text.lines().count());
            return output.toString();
        }
        if (!actual.equals(request.path("expectedSha256").asText())) {
            throw new IllegalStateException("file changed since it was read");
        }
        String replacement = request.path("content").asText();
        if ("REPLACE".equals(operation)) {
            String old = request.path("oldText").asText();
            int index = text.indexOf(old);
            if (old.isEmpty() || index < 0 || text.indexOf(old, index + old.length()) >= 0) {
                throw new IllegalArgumentException("patch target must occur exactly once");
            }
            replacement = text.substring(0, index) + replacement + text.substring(index + old.length());
        }
        byte[] updated = replacement.getBytes(StandardCharsets.UTF_8);
        if (updated.length > 1024 * 1024) {
            throw new IllegalArgumentException("file writes are limited to one MiB");
        }
        // 同目录暂存和原子替换避免留下半个文件；再次核对摘要以检测常见的编辑器并发保存。
        Path temporary = Files.createTempFile(path.getParent(), ".javaclaw-edit-", ".tmp");
        try {
            Files.write(temporary, updated);
            if (Files.isSymbolicLink(path) || !(Files.exists(path) ? digest(bytes(path)) : "MISSING").equals(actual)) {
                throw new IllegalStateException("file changed before commit");
            }
            if (exists) {
                try {
                    Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(path));
                } catch (UnsupportedOperationException windows) {
                    // Windows 由目录 ACL 与沙箱 AppContainer 授权，不伪造 POSIX 权限。
                }
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        output.put("sha256", digest(updated));
        output.put("beforeSha256", actual);
        output.put("created", !exists);
        output.put("bytes", updated.length);
        output.put(
                "diff", "--- " + relative + "\n+++ " + relative + "\n-" + excerpt(text) + "\n+" + excerpt(replacement));
        return output.toString();
    }

    private static byte[] bytes(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("file must be regular and at most sixteen MiB");
        }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("file size changed beyond limit");
            }
            return bytes;
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String excerpt(String value) {
        return value.length() <= 8_000 ? value : value.substring(0, 8_000) + "\n[diff excerpt truncated]";
    }
}
