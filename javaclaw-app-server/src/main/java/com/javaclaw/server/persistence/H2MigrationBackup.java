package com.javaclaw.server.persistence;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;

import com.javaclaw.server.security.PrivateDirectories;

/** 已有 v4 文件库的迁移前一致性备份；不把热数据库文件直接复制，也不自动恢复或删除用户数据。 */
final class H2MigrationBackup {
    private H2MigrationBackup() {}

    static void create(Connection connection, int previous, int target) throws SQLException {
        String database;
        try (var query = connection.createStatement();
                var result = query.executeQuery("CALL DATABASE_PATH()")) {
            result.next();
            database = result.getString(1);
        }
        if (database == null) {
            return; // 内存库没有可恢复的文件，不伪造持久备份成功。
        }
        try {
            Path directory =
                    PrivateDirectories.create(Path.of(database).getParent().resolve("migration-backups"));
            String name = "v" + previous + "-to-v" + target + "-" + UUID.randomUUID();
            Path partial = directory.resolve(name + ".partial");
            Files.createFile(partial);
            ownerFile(partial);
            // BACKUP 由 H2 自己同步 MVStore，保证快照一致；目标使用参数绑定，路径不能注入 SQL。
            try (var backup = connection.prepareStatement("BACKUP TO ?")) {
                backup.setString(1, partial.toString());
                backup.setQueryTimeout(120);
                backup.executeUpdate();
            }
            if (!Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) || Files.size(partial) == 0) {
                throw new IOException("migration backup is empty or not a regular file");
            }
            try (var bytes = FileChannel.open(partial, StandardOpenOption.WRITE)) {
                bytes.force(true);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(partial);
                    var output = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                input.transferTo(output);
            }
            Path archive = directory.resolve(name + ".zip");
            Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE);
            Path checksum = directory.resolve(name + ".zip.sha256");
            Files.writeString(
                    checksum,
                    HexFormat.of().formatHex(digest.digest()) + "  " + archive.getFileName() + "\n",
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            ownerFile(checksum);
            try (var bytes = FileChannel.open(checksum, StandardOpenOption.WRITE)) {
                bytes.force(true);
            }
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            // 部分备份保留用于人工诊断；绝不带着失败的备份继续执行可能隐式提交的 DDL。
            throw new SQLException("cannot create owner-private migration backup; upgrade was not started", failure);
        }
    }

    private static void ownerFile(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
        }
        // Windows 新文件继承 PrivateDirectories 的当前所有者独占 ACL；不授予其他主体读权限。
    }
}
