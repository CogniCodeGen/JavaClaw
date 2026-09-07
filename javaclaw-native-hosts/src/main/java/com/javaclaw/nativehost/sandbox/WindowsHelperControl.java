package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.nativehost.ffm.WindowsNetworkGuardNative;
import com.javaclaw.nativehost.ffm.WindowsSandbox;

/** Windows 批处理 helper 的独立完成通道。目标的 stdout、stderr 和退出码都不是清理证明； 仅平台私有目录中的固定记录可以证明 helper 完成了 ACL 恢复。缺失记录保留证据并失败关闭。 */
final class WindowsHelperControl implements AutoCloseable {
    static final String OPTION = "--control-v1";
    static final String RESULT = "execution-result-v1";
    private static final String PREVIOUS = "previous-evidence-v1";
    private static final String PREFIX = "JAVACLAW-SANDBOX-V1\n";
    private final Path directory;
    private boolean started;

    private WindowsHelperControl(Path directory) {
        this.directory = directory;
    }

    static WindowsHelperControl open() throws IOException {
        Path directory = Files.createTempDirectory("javaclaw-sandbox-evidence-").toRealPath();
        try {
            WindowsNetworkGuardNative.protectControlDirectory(directory);
            return new WindowsHelperControl(directory);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(directory);
            throw failure;
        }
    }

    Path directory() {
        return directory;
    }

    void started() {
        started = true;
    }

    void requestTermination(Process process) throws IOException, InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        Path request = directory.resolve(WindowsProxyCleanup.TERMINATE);
        if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)) {
            Files.write(request, new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    static void completed(Path directory, int exitCode) throws IOException {
        write(directory, PREFIX + "COMPLETED\n" + exitCode + "\n");
    }

    static void failed(Path directory, boolean restoration) throws IOException {
        write(directory, PREFIX + (restoration ? "RESTORATION_FAILED\n" : "EXECUTION_FAILED\n"));
    }

    static void failed(Path directory, Throwable failure) throws IOException {
        var evidence = SandboxIsolationFailure.evidence(failure);
        if (evidence.isPresent() && !evidence.orElseThrow().isEmpty()) {
            String paths = evidence.orElseThrow().stream()
                    .limit(32)
                    .map(path -> java.util.Base64.getEncoder()
                            .encodeToString(path.toString().getBytes(StandardCharsets.UTF_8)))
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (paths.length() > 65_536) {
                throw new IOException("Windows ACL evidence reference exceeds control bound");
            }
            Files.writeString(
                    directory.resolve(PREVIOUS),
                    paths,
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
        }
        failed(directory, evidence.isPresent());
    }

    private static void write(Path directory, String result) throws IOException {
        Files.writeString(
                directory.resolve(RESULT),
                result,
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
    }

    static void verify(Path directory) throws IOException {
        Path result = directory.resolve(RESULT);
        if (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS) || Files.size(result) > 128) {
            throw uncertain(directory, null);
        }
        String status = Files.readString(result, StandardCharsets.US_ASCII);
        if (status.matches(PREFIX + "COMPLETED\n-?[0-9]{1,10}\n") || status.equals(PREFIX + "EXECUTION_FAILED\n")) {
            return;
        }
        var failure = uncertain(directory, null);
        Path previous = directory.resolve(PREVIOUS);
        if (Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS) && Files.size(previous) <= 65_536) {
            for (String encoded : Files.readAllLines(previous, StandardCharsets.US_ASCII)) {
                try {
                    Path path =
                            Path.of(new String(java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
                    if (path.isAbsolute()) {
                        failure.addSuppressed(uncertain(path, null));
                    }
                } catch (IllegalArgumentException malformed) {
                    failure.addSuppressed(malformed);
                }
            }
        }
        throw failure;
    }

    static boolean restorationFailure(Throwable failure) {
        return restorationFailure(failure, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean restorationFailure(Throwable failure, Set<Throwable> visited) {
        if (failure == null || !visited.add(failure) || visited.size() > 256) {
            return false;
        }
        if (failure instanceof WindowsSandbox.AclRestorationException
                || restorationFailure(failure.getCause(), visited)) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (restorationFailure(suppressed, visited)) {
                return true;
            }
        }
        return false;
    }

    private static WindowsSandbox.AclRestorationException uncertain(Path directory, Throwable cause) {
        return new WindowsSandbox.AclRestorationException(
                "Windows Sandbox ACL restoration is unconfirmed; Workspace must remain locked",
                cause,
                Optional.of(directory));
    }

    @Override
    public void close() throws IOException {
        if (started) {
            try {
                verify(directory);
            } catch (IOException failure) {
                if (failure instanceof WindowsSandbox.AclRestorationException uncertain) {
                    throw uncertain;
                }
                throw uncertain(directory, failure);
            }
        }
        // 成功后可删除完成记录；基线凭据由其拥有者收束，失败目录永不在这里删除。
        Files.deleteIfExists(directory.resolve(RESULT));
        Files.deleteIfExists(directory.resolve(PREVIOUS));
        Files.deleteIfExists(directory.resolve(WindowsProxyCleanup.TERMINATE));
        try (var children = Files.list(directory)) {
            if (children.findAny().isEmpty()) {
                Files.delete(directory);
            }
        }
    }
}
