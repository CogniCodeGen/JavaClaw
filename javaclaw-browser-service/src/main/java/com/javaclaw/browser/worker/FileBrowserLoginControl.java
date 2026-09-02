package com.javaclaw.browser.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** 只读取 Worker 临时根中单个固定文件的登录控制通道。 */
final class FileBrowserLoginControl implements BrowserLoginControl {
    static final String CONTROL_ROOT_ENVIRONMENT = "JAVACLAW_BROWSER_CONTROL_ROOT";

    private final Path controlFile;

    FileBrowserLoginControl(Path controlRoot, String sessionId) {
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
        controlFile = root.resolve("login-" + checkedId + ".control").normalize();
        if (!controlFile.getParent().equals(root)) {
            throw new SecurityException("Browser login control file escapes its root");
        }
    }

    @Override
    public Decision decision() {
        if (!Files.exists(controlFile, LinkOption.NOFOLLOW_LINKS)) {
            return Decision.WAIT;
        }
        if (!Files.isRegularFile(controlFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("Browser login control path is not a regular file");
        }
        try {
            long size = Files.size(controlFile);
            if (size < 1 || size > 16) {
                throw new SecurityException("Browser login control value is invalid");
            }
            return switch (Files.readString(controlFile, StandardCharsets.US_ASCII)
                    .strip()) {
                case "SAVE" -> Decision.SAVE;
                case "CANCEL" -> Decision.CANCEL;
                default -> throw new SecurityException("Browser login control value is unknown");
            };
        } catch (IOException failure) {
            throw new IllegalStateException("Browser login control file cannot be read", failure);
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
