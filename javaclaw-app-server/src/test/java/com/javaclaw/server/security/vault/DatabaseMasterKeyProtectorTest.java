package com.javaclaw.server.security.vault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.credential.MasterKeyProtectionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMasterKeyProtectorTest {
    @TempDir
    Path directory;

    @Test
    void 新数据库保存和重启读取不共享调用方数组() throws Exception {
        var fixture = new DatabaseVaultFixture(directory);
        var local = fixture.local();
        byte[] original = DatabaseVaultFixture.key(7);
        assertTrue(local.load("unknown").isEmpty());
        assertEquals(0, fixture.keyCount());
        local.store("first", original);
        assertArrayEquals(DatabaseVaultFixture.key(7), original);
        Arrays.fill(original, (byte) 0);
        byte[] loaded = local.load("first").orElseThrow();
        assertArrayEquals(DatabaseVaultFixture.key(7), loaded);
        Arrays.fill(loaded, (byte) 0);
        assertArrayEquals(
                DatabaseVaultFixture.key(7), fixture.local().load("first").orElseThrow());
        assertEquals(1, fixture.keyCount());
    }

    @Test
    void 替换和重复删除仅作用于指定本地主密钥() throws Exception {
        var fixture = new DatabaseVaultFixture(directory);
        var local = fixture.local();
        local.store("first", DatabaseVaultFixture.key(7));
        local.store("second", DatabaseVaultFixture.key(8));
        local.store("first", DatabaseVaultFixture.key(9));
        assertArrayEquals(DatabaseVaultFixture.key(9), local.load("first").orElseThrow());
        local.delete("first");
        local.delete("first");
        assertTrue(local.load("first").isEmpty());
        assertArrayEquals(DatabaseVaultFixture.key(8), local.load("second").orElseThrow());
        assertEquals(1, fixture.keyCount());
    }

    @Test
    void 非法标识和非256位主密钥在写入之前拒绝() throws Exception {
        var fixture = new DatabaseVaultFixture(directory);
        var local = fixture.local();
        assertThrows(NullPointerException.class, () -> new DatabaseMasterKeyProtector(null));
        assertThrows(IllegalArgumentException.class, () -> local.load("../outside"));
        assertThrows(IllegalArgumentException.class, () -> local.store("a/b", DatabaseVaultFixture.key(1)));
        assertThrows(IllegalArgumentException.class, () -> local.delete(""));
        assertThrows(NullPointerException.class, () -> local.load(null));
        assertThrows(NullPointerException.class, () -> local.store("valid", null));
        byte[] invalid = new byte[] {1, 2};
        assertThrows(MasterKeyProtectionException.class, () -> local.store("valid", invalid));
        assertArrayEquals(new byte[] {1, 2}, invalid);
        assertEquals(0, fixture.keyCount());
    }

    @Test
    void 数据库拒绝保存时保留原值且公共异常不携带SQL参数链() throws Exception {
        var fixture = new DatabaseVaultFixture(directory);
        var local = fixture.local();
        local.store("existing", DatabaseVaultFixture.key(9));
        fixture.sql("ALTER TABLE CORE.VAULT_LOCAL_MASTER_KEY ADD CONSTRAINT TEST_REJECT_NEW CHECK(KEY_ID='existing')");
        byte[] candidate = DatabaseVaultFixture.key(15);
        var failure = assertThrows(MasterKeyProtectionException.class, () -> local.store("candidate", candidate));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertArrayEquals(DatabaseVaultFixture.key(15), candidate);
        assertArrayEquals(DatabaseVaultFixture.key(9), local.load("existing").orElseThrow());
        assertTrue(local.load("candidate").isEmpty());
        assertEquals(1, fixture.keyCount());
    }

    @Test
    void 数据库结构不可用时读取与删除也脱敏失败() throws Exception {
        var fixture = new DatabaseVaultFixture(directory);
        fixture.sql("DROP TABLE CORE.VAULT_LOCAL_MASTER_KEY");
        var local = fixture.local();
        assertNull(assertThrows(MasterKeyProtectionException.class, () -> local.load("candidate"))
                .getCause());
        assertNull(assertThrows(MasterKeyProtectionException.class, () -> local.delete("candidate"))
                .getCause());
    }

    @Test
    void 主密钥列损坏时驱动错误也不得把密钥写入trace文件() throws Exception {
        var fixture = new DatabaseVaultFixture(directory);
        // H2 的 22001 错误会在原始消息中包含完整字节值；使用固定假密钥验证驱动层日志边界。
        fixture.sql("ALTER TABLE CORE.VAULT_LOCAL_MASTER_KEY ALTER COLUMN KEY_BYTES VARBINARY(31)");
        byte[] fakeKey = DatabaseVaultFixture.key(0x5a);
        var failure = assertThrows(
                MasterKeyProtectionException.class, () -> fixture.local().store("fixed-fake-key", fakeKey));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains(HexFormat.of().formatHex(fakeKey)));
        assertFalse(failure.toString().contains("Z".repeat(32)));
        assertFalse(Files.exists(fixture.database.dataRoot().resolve("javaclaw.trace.db")));
        assertFalse(Files.exists(fixture.database.dataRoot().resolve("javaclaw.trace.db.old")));
        assertEquals(0, fixture.keyCount());
    }
}
