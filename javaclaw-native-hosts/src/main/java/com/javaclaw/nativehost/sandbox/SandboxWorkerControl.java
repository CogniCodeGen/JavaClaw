package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

/** 宿主与独立监护 helper 的私有控制目录；目标 Worker 的文件权限不得包含此目录。 */
final class SandboxWorkerControl implements AutoCloseable {
    private final Path directory;
    private final Object identity;
    private long revision;
    private boolean closed;

    private SandboxWorkerControl(Path directory) throws IOException {
        this.directory = directory.toRealPath();
        if (!directory.toAbsolutePath().normalize().equals(this.directory)) {
            throw new IOException("Worker lease control directory must not contain symbolic links");
        }
        identity = identity(this.directory);
    }

    static SandboxWorkerControl create() throws IOException {
        Path directory;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            directory = Files.createTempDirectory("javaclaw-worker-lease-");
        } else {
            directory = Files.createTempDirectory(
                    "javaclaw-worker-lease-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        SandboxWorkerControl result = new SandboxWorkerControl(directory.toRealPath());
        result.touch();
        return result;
    }

    static SandboxWorkerControl open(Path directory) throws IOException {
        return new SandboxWorkerControl(directory);
    }

    Path directory() {
        return directory;
    }

    synchronized void touch() throws IOException {
        write("control", "RUN:" + ++revision);
    }

    synchronized void stop() throws IOException {
        write("control", "CLOSE");
    }

    String state() throws IOException {
        return read("control");
    }

    String status() throws IOException {
        return read("status");
    }

    void status(String value) throws IOException {
        write("status", value);
    }

    private synchronized String read(String name) throws IOException {
        verify();
        Path path = directory.resolve(name);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 512) {
            throw new IOException("Worker lease control file is unsafe");
        }
        return Files.readString(path, StandardCharsets.US_ASCII);
    }

    private synchronized void write(String name, String value) throws IOException {
        verify();
        Path pending = directory.resolve(name + ".pending");
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Worker lease pending file already exists");
        }
        Files.writeString(pending, value, StandardCharsets.US_ASCII, java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.move(
                pending, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void verify() throws IOException {
        if (closed || !identity.equals(identity(directory))) {
            throw new IOException("Worker lease control directory identity changed");
        }
    }

    private static Object identity(Path root) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.fileKey() == null) {
            throw new IOException("Worker lease requires a real identifiable directory");
        }
        return attributes.fileKey();
    }

    /** 仅在监护 helper 退出后由宿主调用；只清除约定文件，不递归处理其他内容。 */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        verify();
        for (String name : new String[] {"control", "status", "control.pending", "status.pending"}) {
            Files.deleteIfExists(directory.resolve(name));
        }
        Files.delete(directory);
        closed = true;
    }
}
