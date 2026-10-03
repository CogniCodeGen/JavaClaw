package com.javaclaw.platform.data;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds stable home and legacy data locks while the application format is prepared. */
public final class ApplicationUpgradeGuard implements AutoCloseable {
    private static final String ROOT_LOCK = ".javaclaw-bootstrap.lock";
    public static final String DATA_LOCK = "javaclaw.instance.lock";

    private final Path home;
    private final FileChannel rootChannel;
    private final FileLock rootLock;
    private FileChannel dataChannel;
    private FileLock dataLock;

    private ApplicationUpgradeGuard(Path home, FileChannel rootChannel, FileLock rootLock,
                                    FileChannel dataChannel, FileLock dataLock) {
        this.home = home;
        this.rootChannel = rootChannel;
        this.rootLock = rootLock;
        this.dataChannel = dataChannel;
        this.dataLock = dataLock;
    }

    /** Null means another new-version process holds the home lock. */
    public static ApplicationUpgradeGuard acquire(ApplicationHome applicationHome)
            throws IOException {
        Path home = applicationHome.root();
        if (Files.isSymbolicLink(home) || !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("应用目录必须是普通目录: " + home);
        }
        Path rootLockPath = applicationHome.requireManaged(home.resolve(ROOT_LOCK));
        FileChannel rootChannel = FileChannel.open(rootLockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock rootLock = tryLock(rootChannel);
        if (rootLock == null) {
            rootChannel.close();
            return null;
        }
        try {
            Path data = applicationHome.requireManaged(applicationHome.dataDirectory());
            if (Files.exists(data, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("应用数据路径不是普通目录: " + data);
            }
            Files.createDirectories(data);
            Path dataLockPath = applicationHome.requireManaged(data.resolve(DATA_LOCK));
            FileChannel dataChannel = FileChannel.open(dataLockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock dataLock = tryLock(dataChannel);
                if (dataLock == null) throw new AlreadyRunningException();
                return new ApplicationUpgradeGuard(home, rootChannel, rootLock,
                        dataChannel, dataLock);
            } catch (IOException | RuntimeException failure) {
                dataChannel.close();
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            rootLock.release();
            rootChannel.close();
            throw failure;
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try { return channel.tryLock(); }
        catch (OverlappingFileLockException heldHere) { return null; }
    }

    public void requireHome(ApplicationHome applicationHome) {
        if (!home.equals(applicationHome.root()) || !rootLock.isValid()
                || dataLock == null || !dataLock.isValid()) {
            throw new IllegalStateException("升级锁与应用目录不匹配或已经释放");
        }
    }

    /** Transfer the data lock without releasing it; old launchers remain excluded. */
    public DataLock takeDataLock() {
        if (dataLock == null || !dataLock.isValid()) {
            throw new IllegalStateException("应用数据锁已经转移");
        }
        DataLock lease = new DataLock(dataChannel, dataLock);
        dataChannel = null;
        dataLock = null;
        return lease;
    }

    @Override public void close() throws IOException {
        IOException failure = null;
        if (dataLock != null) {
            try { dataLock.release(); } catch (IOException e) { failure = e; }
        }
        if (dataChannel != null) {
            try { dataChannel.close(); } catch (IOException e) { failure = e; }
        }
        try { rootLock.release(); } catch (IOException e) { failure = e; }
        try { rootChannel.close(); } catch (IOException e) { failure = e; }
        if (failure != null) throw failure;
    }

    public record DataLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException {
            try { if (lock.isValid()) lock.release(); }
            finally { channel.close(); }
        }
    }

    public static final class AlreadyRunningException extends IOException {
        public AlreadyRunningException() {
            super("JavaClaw 实例仍在运行；请退出旧版本后重试升级");
        }
    }
}
