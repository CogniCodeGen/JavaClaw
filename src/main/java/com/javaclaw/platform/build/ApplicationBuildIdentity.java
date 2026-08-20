package com.javaclaw.platform.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Identifies the exact application classes/resources visible to a launched JVM.
 *
 * <p>Development launches point at a mutable classes directory, while packaged launches point at
 * the application JAR. Capturing a content fingerprint lets the UI distinguish an actual FXML
 * defect from an old JVM reading resources that Maven has replaced underneath it.</p>
 *
 * <p>This is a coherence diagnostic, not a trust boundary. Failure to fingerprint never prevents
 * startup; security-sensitive artifact verification remains the responsibility of plugin/runtime
 * verification.</p>
 */
public final class ApplicationBuildIdentity {

    private static final Logger log = LoggerFactory.getLogger(ApplicationBuildIdentity.class);
    private static final AtomicReference<ApplicationBuildIdentity> LAUNCHED =
            new AtomicReference<>();

    private final Path codeSource;
    private final String fingerprint;
    private final boolean available;

    private ApplicationBuildIdentity(Path codeSource, String fingerprint, boolean available) {
        this.codeSource = codeSource;
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.available = available;
    }

    /** Captures and records the build used by the process launcher. */
    public static ApplicationBuildIdentity launch(Class<?> anchor) {
        ApplicationBuildIdentity captured = capture(anchor);
        LAUNCHED.set(captured);
        return captured;
    }

    /** Returns the launcher snapshot, or captures one for tests/embedded startup paths. */
    public static ApplicationBuildIdentity launchedOrCapture(Class<?> anchor) {
        ApplicationBuildIdentity existing = LAUNCHED.get();
        if (existing != null) return existing;
        ApplicationBuildIdentity captured = capture(anchor);
        if (LAUNCHED.compareAndSet(null, captured)) return captured;
        return LAUNCHED.get();
    }

    /** Best-effort capture from the anchor's code source. */
    public static ApplicationBuildIdentity capture(Class<?> anchor) {
        Objects.requireNonNull(anchor, "anchor");
        Path source = null;
        try {
            var protection = anchor.getProtectionDomain();
            var codeSource = protection == null ? null : protection.getCodeSource();
            var location = codeSource == null ? null : codeSource.getLocation();
            if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
                log.warn("无法确定应用代码源，禁用构建一致性检测: {}", location);
                return unavailable(null);
            }
            source = Path.of(location.toURI()).toAbsolutePath().normalize();
            return fromPath(source);
        } catch (IOException | URISyntaxException | RuntimeException failure) {
            log.warn("无法计算应用构建指纹，禁用构建一致性检测: {}", failure.getMessage());
            return unavailable(source);
        }
    }

    /** Strict path-based capture used by focused tests. */
    public static ApplicationBuildIdentity fromPath(Path source) throws IOException {
        Path normalized = Objects.requireNonNull(source, "source")
                .toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("应用代码源不存在: " + normalized);
        }
        return new ApplicationBuildIdentity(normalized, fingerprint(normalized), true);
    }

    public String fingerprint() {
        return fingerprint;
    }

    public String shortFingerprint() {
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
    }

    public boolean available() {
        return available;
    }

    /** Returns true only when a fresh, successful fingerprint proves that files changed. */
    public boolean hasChanged() {
        if (!available || codeSource == null) return false;
        try {
            return !fingerprint.equals(fingerprint(codeSource));
        } catch (IOException | RuntimeException failure) {
            log.warn("重新检查应用构建指纹失败，保留原始错误: {}", failure.getMessage());
            return false;
        }
    }

    private static ApplicationBuildIdentity unavailable(Path source) {
        return new ApplicationBuildIdentity(source, "unavailable", false);
    }

    private static String fingerprint(Path source) throws IOException {
        MessageDigest digest = sha256();
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(source)) {
                files = walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .sorted(Comparator.comparing(path ->
                                source.relativize(path).toString().replace('\\', '/')))
                        .toList();
            }
            for (Path file : files) {
                String relative = source.relativize(file).toString().replace('\\', '/');
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                updateFile(digest, file);
                digest.update((byte) 0xff);
            }
        } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            updateFile(digest, source);
        } else {
            throw new IOException("应用代码源既不是目录也不是普通文件: " + source);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateFile(MessageDigest digest, Path file) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", impossible);
        }
    }
}
