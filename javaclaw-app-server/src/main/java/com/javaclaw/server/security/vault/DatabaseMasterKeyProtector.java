package com.javaclaw.server.security.vault;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.nativehost.credential.MasterKeyProtectionException;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;

/**
 * 在当前 data-v6 的 H2 中保存 Vault 主密钥，不访问系统凭据或导入其他存储。
 *
 * <p>主密钥与业务密文位于同一数据库，保密边界是数据目录访问权限；不提供系统钥匙串的独立保护。候选主密钥使用独立事务， 保持 Vault 先准备主密钥、再原子提交密文与回执、最后清理旧主密钥的顺序。
 */
public final class DatabaseMasterKeyProtector implements MasterKeyProtector {
    private final H2Transactions transactions;
    private final LocalMasterKeyRepository repository = new LocalMasterKeyRepository();

    /**
     * 创建本地数据库主密钥端口；不会自动生成或修复缺失主密钥。
     *
     * @param database 已初始化当前 schema 的数据库
     */
    public DatabaseMasterKeyProtector(H2Database database) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
    }

    /**
     * 返回调用方拥有的主密钥副本；缺失只返回空，不自动生成替代密钥。
     *
     * @param keyId 当前安装的主密钥标识
     * @return 主密钥副本；未知或丢失的标识返回空
     * @throws MasterKeyProtectionException 数据库不可用或保存的数据长度无效
     */
    @Override
    public Optional<byte[]> load(String keyId) {
        String checked = keyId(keyId);
        byte[][] owned = new byte[1][];
        try {
            execute(connection -> {
                owned[0] = repository.find(connection, checked).orElse(null);
                if (owned[0] != null) {
                    requireKey(owned[0]);
                }
                return null;
            });
            return owned[0] == null ? Optional.empty() : Optional.of(owned[0].clone());
        } finally {
            if (owned[0] != null) {
                Arrays.fill(owned[0], (byte) 0);
            }
        }
    }

    /**
     * 独立提交候选主密钥；不修改 Vault 活动指针、业务密文或命令回执。
     *
     * @param keyId 不含路径的主密钥标识
     * @param key 32 字节主密钥；调用方数组保持不变，内部副本在返回前清零
     */
    @Override
    public void store(String keyId, byte[] key) {
        String checked = keyId(keyId);
        byte[] copy = Objects.requireNonNull(key, "key").clone();
        try {
            requireKey(copy);
            execute(connection -> {
                repository.store(connection, checked, copy);
                return null;
            });
        } finally {
            Arrays.fill(copy, (byte) 0);
        }
    }

    /**
     * 删除本地主密钥记录；缺失视为已删除，不会访问任何其他存储。
     *
     * @param keyId 已退役或尚未被 Vault 引用的候选标识
     */
    @Override
    public void delete(String keyId) {
        String checked = keyId(keyId);
        execute(connection -> {
            repository.delete(connection, checked);
            return null;
        });
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (Exception failure) {
            // JDBC 异常可能携带参数；不能保留其消息、cause 或 suppressed 链。
            throw new MasterKeyProtectionException("本地数据库主密钥操作失败");
        }
    }

    private static String keyId(String value) {
        String checked = Objects.requireNonNull(value, "keyId").strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            throw new IllegalArgumentException("主密钥标识格式无效");
        }
        return checked;
    }

    private static void requireKey(byte[] key) {
        if (key.length != VaultCipher.KEY_BYTES) {
            throw new MasterKeyProtectionException("主密钥必须包含 32 字节");
        }
    }
}
