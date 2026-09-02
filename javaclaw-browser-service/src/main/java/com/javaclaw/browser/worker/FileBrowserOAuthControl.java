package com.javaclaw.browser.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** 只读取 Worker 临时根中单个固定文件的 OAuth 取消通道。 */
final class FileBrowserOAuthControl implements BrowserLoginControl {
    private final Path controlFile;

    FileBrowserOAuthControl(Path controlRoot, String sessionId) {
        Path root = Objects.requireNonNull(controlRoot, "controlRoot")
                .toAbsolutePath()
                .normalize();
        String checkedId;
        try {
            checkedId = java.util
                    .UUID
                    .fromString(Objects.requireNonNull(sessionId, "sessionId"))
                    .toString();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("sessionId must be a UUID", failure);
        }
        controlFile = root.resolve("oauth-" + checkedId + ".control").normalize();
        if (!controlFile.getParent().equals(root)) {
            throw new SecurityException("Browser OAuth control file escapes its root");
        }
    }

    @Override
    public Decision decision() {
        if (!Files.exists(controlFile, LinkOption.NOFOLLOW_LINKS)) {
            return Decision.WAIT;
        }
        if (!Files.isRegularFile(controlFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("Browser OAuth control path is not a regular file");
        }
        try {
            String value =
                    Files.readString(controlFile, StandardCharsets.US_ASCII).strip();
            if (!"CANCEL".equals(value) || Files.size(controlFile) > 16) {
                throw new SecurityException("Browser OAuth control value is invalid");
            }
            return Decision.CANCEL;
        } catch (IOException failure) {
            throw new IllegalStateException("Browser OAuth control file cannot be read", failure);
        }
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(controlFile);
        } catch (IOException ignored) {
            // 临时根由宿主生命周期统一回收，删除失败不扩大权限。
        }
    }
}
