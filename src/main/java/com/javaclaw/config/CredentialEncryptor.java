package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;

/**
 * 敏感凭据加密工具 — 基于 AES-256-GCM 的对称加密
 *
 * <p>对 API Key、密码等敏感信息进行加密存储。加密后的字符串以 {@code ENC()} 包裹，
 * 便于识别已加密的配置项。每次加密使用随机盐 + 随机 IV，相同明文产生不同密文。</p>
 *
 * <p><b>密钥来源</b>：全局 H2 库 {@code app_state} 表内的随机主密钥（首次使用时生成，
 * key={@code credential.master.key}）→ 逐值随机盐 PBKDF2 → AES-256。<b>密钥随库一起备份/迁移</b>，
 * 不再依赖外部文件（旧版曾用 {@code data/credential.key} 外部文件，丢文件即丢全部凭据；首次访问
 * 若检测到该旧文件会一次性迁移进 H2 后不再依赖它）。更早的实现以「用户名 + 主机名」派生口令——
 * macOS 无 {@code /etc/hostname}，走 {@code InetAddress.getLocalHost().getHostName()} 的主机名随
 * 网络环境漂移（换 Wi-Fi/DHCP/DNS 反解即变），密钥随之改变导致既有密文集体 Tag mismatch。</p>
 *
 * <p><b>解密失败策略</b>：主密钥失败后用旧版设备口令做一次回退尝试（主机名未变的用户无感迁移）；
 * 两把钥匙都打不开时<b>原样返回 ENC(...) 密文</b>（不返回空串）。这是刻意的<b>非破坏</b>选择——
 * 返回空串会让上层「重新加密全部内存凭据回写」的保存路径把 {@code encrypt("")==""} 覆盖掉好端端的
 * 密文，一次瞬时失败（主机名漂移/库临时不可读）即永久毁掉凭据；返回原密文则 {@code encrypt()} 的
 * {@code isEncrypted} 守卫会跳过、密文完好，条件恢复后又能解开。代价是 ENC(...) 原文可能被当作凭据
 * 发出去产生一次 401（可自愈），远轻于不可逆的数据丢失。</p>
 *
 * @author JavaClaw
 */
public final class CredentialEncryptor {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptor.class);

    /** 加密标识前缀和后缀 */
    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";

    /** AES-GCM 参数 */
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 65536;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** app_state 表内主密钥的 key。 */
    private static final String KEY_STATE_KEY = "credential.master.key";

    /** 旧版外部主密钥文件（统一数据根/credential.key）：仅在首次访问时读一次以迁移进 H2，此后不再写。 */
    private static final Path LEGACY_KEY_FILE =
            AppDatabase.dataDirectory().resolve("credential.key");

    static Path legacyKeyFilePath() {
        return LEGACY_KEY_FILE;
    }

    /** 主密钥缓存（每次加解密都要 PBKDF2，文件只读一次） */
    private static volatile String cachedMasterSecret;

    /**
     * 预热主密钥：在启动时（H2 确定可用）解析并缓存持久主密钥。
     *
     * <p>作用是杜绝这条丢凭据路径：若首次用到密钥恰逢某次瞬时 H2 不可用，{@link #masterPassphrase(boolean)}
     * 旧实现会回退到随主机名漂移的设备口令，使故障窗口内的新密文可能永久无法解开。
     * 现在加密路径必须取得持久主密钥，只有解密兼容路径允许旧口令回退。由 {@code JavaClawApp.start} 在
     * {@code WorkspaceManager.init()}（建库）之后调用。</p>
     */
    public static void warmUpMasterKey() {
        try {
            masterPassphrase(false);
            if (cachedMasterSecret != null) {
                log.info("凭据主密钥已预热（持久密钥已缓存，此后加密不会回退漂移口令）");
            } else {
                log.warn("凭据主密钥预热未取得持久密钥（H2 暂不可用？），将按需重试");
            }
        } catch (Exception e) {
            log.warn("凭据主密钥预热异常（忽略，按需重试）: {}", e.getMessage());
        }
    }

    private CredentialEncryptor() {
    }

    /**
     * 判断值是否已加密
     */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    /**
     * 加密明文。如果已经是加密格式则原样返回。
     *
     * @param plainText 明文
     * @return ENC(base64密文) 格式字符串，明文为空时原样返回
     */
    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank() || isEncrypted(plainText)) {
            return plainText;
        }
        try {
            byte[] salt = new byte[SALT_LENGTH];
            SECURE_RANDOM.nextBytes(salt);

            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            SecretKey key = deriveKey(salt, masterPassphrase(false));
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 格式：salt(16) + iv(12) + cipherText
            ByteBuffer buffer = ByteBuffer.allocate(SALT_LENGTH + IV_LENGTH + cipherText.length);
            buffer.put(salt);
            buffer.put(iv);
            buffer.put(cipherText);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(buffer.array()) + ENC_SUFFIX;
        } catch (Exception e) {
            log.error("加密失败，已拒绝返回明文", e);
            throw new IllegalStateException("凭据加密失败，未保存明文", e);
        }
    }

    /**
     * 解密密文。如果不是加密格式则原样返回（兼容旧的明文配置）。
     *
     * @param encryptedText ENC(base64密文) 格式字符串
     * @return 明文
     */
    public static String decrypt(String encryptedText) {
        if (encryptedText == null || !isEncrypted(encryptedText)) {
            return encryptedText;
        }
        byte[] salt;
        byte[] iv;
        byte[] cipherText;
        try {
            String base64 = encryptedText.substring(ENC_PREFIX.length(),
                    encryptedText.length() - ENC_SUFFIX.length());
            byte[] decoded = Base64.getDecoder().decode(base64);

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            salt = new byte[SALT_LENGTH];
            iv = new byte[IV_LENGTH];
            buffer.get(salt);
            buffer.get(iv);
            cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
        } catch (Exception e) {
            // 密文结构损坏：原样返回（非破坏）——返回空串会在重存时把这行覆盖成空，彻底毁掉
            log.error("密文格式损坏，无法解密（请在设置中重新填写该凭据）", e);
            return encryptedText;
        }

        // 1) 主密钥（H2 库内，当前方案）
        try {
            return doDecrypt(salt, iv, cipherText, masterPassphrase(true));
        } catch (Exception primaryFailure) {
            // 2) 旧版设备派生口令（用户名@主机名）：主机名未漂移的存量密文可无感解开
            try {
                String plain = doDecrypt(salt, iv, cipherText, legacyPassphrase());
                log.info("凭据经旧版设备派生密钥解密成功；建议在设置中重新保存一次以迁移到 H2 主密钥"
                        + "（主机名漂移后旧密文将无法解密）");
                return plain;
            } catch (Exception legacyFailure) {
                // 两把钥匙都打不开（密钥临时不可读 / 主机名漂移 / 跨机迁移未带密钥）：原样返回 ENC(...)
                // 密文，绝不返回空串——空串会在上层「重新加密全部内存凭据回写」时把好端端的密文覆盖成空，
                // 一次瞬时失败即永久毁掉凭据。返回原密文则 encrypt() 的 isEncrypted 守卫跳过、密文完好，
                // 条件恢复后又能解开；代价是 ENC 原文可能被当凭据发出去产生一次可自愈的 401
                log.error("凭据解密失败（主密钥与旧版设备密钥均不匹配），原样保留密文（非破坏）。"
                        + "若为跨机迁移请确认已带上主密钥（现随 H2 库一起存储）；否则请在「设置」中重新填写该凭据",
                        primaryFailure);
                return encryptedText;
            }
        }
    }

    /** 以指定口令派生密钥并解密（失败抛异常，由调用方决定回退策略）。 */
    private static String doDecrypt(byte[] salt, byte[] iv, byte[] cipherText, String passphrase)
            throws Exception {
        SecretKey key = deriveKey(salt, passphrase);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    /**
     * 主密钥口令：从 H2 库读取（或首次生成）随机主密钥，密钥随库一起备份/迁移。
     *
     * <p>取值优先级：① H2 {@code app_state} 已有 → 用；② 库内无但存在旧版外部密钥文件 →
     * 一次性迁移进 H2（保住用文件密钥加密的存量凭据），此后不再依赖文件；③ 全新 → 生成随机密钥
     * 并<b>创建互斥</b>写入 H2（{@code INSERT} 而非 {@code MERGE}——PK 冲突即另一并发进程已写入，
     * 读回胜者密钥收敛到同一把钥匙，杜绝 AUTO_SERVER 多进程首启各用不同密钥、败者重启解不开的丢凭据）。</p>
     *
     * <p><b>失败不缓存</b>：库读写失败（临时不可用等）本次降级旧版设备派生口令但<b>不缓存</b>——
     * 缓存会让整个 JVM 生命周期都用漂移的主机名口令加密新凭据，库恢复后重启就再也解不开；不缓存则
     * 下次调用重试，故障窗口一过即自愈。</p>
     */
    private static String masterPassphrase(boolean allowLegacyFallback) {
        String cached = cachedMasterSecret;
        if (cached != null) {
            return cached;
        }
        synchronized (CredentialEncryptor.class) {
            if (cachedMasterSecret != null) {
                return cachedMasterSecret;
            }
            try {
                // ① H2 库内已有
                String fromDb = readKeyFromDb();
                if (fromDb != null && !fromDb.isBlank()) {
                    cachedMasterSecret = fromDb;
                    return fromDb;
                }
                // ② 库内无、但存在旧版外部密钥文件 → 迁移进 H2（保住用文件密钥加密的存量凭据）
                String fromFile = readLegacyKeyFile();
                if (fromFile != null) {
                    String adopted = insertKeyOrAdopt(fromFile);
                    log.info("已把旧版外部主密钥文件迁移进 H2（后续不再依赖该文件，可安全删除）: {}", LEGACY_KEY_FILE);
                    cachedMasterSecret = adopted;
                    return adopted;
                }
                // ③ 全新：生成并创建互斥写入 H2
                byte[] secretBytes = new byte[32];
                SECURE_RANDOM.nextBytes(secretBytes);
                String candidate = Base64.getEncoder().encodeToString(secretBytes);
                String secret = insertKeyOrAdopt(candidate);
                log.info("已在 H2 生成凭据主密钥（随库备份/迁移，不再依赖外部文件）");
                cachedMasterSecret = secret;
                return secret;
            } catch (Exception e) {
                // 不缓存 fallback：下次调用重试 H2，避免瞬时故障毒化整个 JVM 生命周期
                if (allowLegacyFallback) {
                    log.error("H2 主密钥读写失败，本次解密回退旧版设备派生口令（不缓存）", e);
                    return legacyPassphrase();
                }
                throw new IllegalStateException("H2 持久主密钥不可用，拒绝用临时口令加密", e);
            }
        }
    }

    /** 从 H2 {@code app_state} 读取主密钥；不存在/异常返回 null。 */
    private static String readKeyFromDb() {
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT state_value FROM app_state WHERE state_key = ?")) {
            ps.setString(1, KEY_STATE_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString("state_value");
                    return v == null ? null : v.trim();
                }
            }
        } catch (Exception e) {
            log.warn("读取 H2 主密钥失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 把 candidate 作为主密钥<b>创建互斥</b>写入 H2：{@code INSERT}（非 MERGE，绝不覆盖既有），
     * 成功即赢家返回 candidate；PK 冲突表示另一并发进程/线程已写入，读回其密钥为准（收敛到同一把钥匙）。
     *
     * @return 本次写入或既有的胜者密钥
     * @throws Exception 库不可用、或冲突后仍读不回胜者密钥（交上层降级）
     */
    private static String insertKeyOrAdopt(String candidate) throws Exception {
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO app_state(state_key, state_value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, KEY_STATE_KEY);
            ps.setString(2, candidate);
            ps.executeUpdate();
            return candidate; // 抢占成功
        } catch (Exception insertFailed) {
            // 多为主键冲突（另一方已写入）：读回胜者密钥；读得到则采纳，否则是真故障，上抛降级
            String winner = readKeyFromDb();
            if (winner != null && !winner.isBlank()) {
                log.info("H2 主密钥已由并发方写入，采用其密钥");
                return winner;
            }
            throw insertFailed;
        }
    }

    /** 读取旧版外部主密钥文件（仅用于一次性迁移进 H2）；不存在/空白/异常返回 null。 */
    private static String readLegacyKeyFile() {
        try {
            if (Files.exists(LEGACY_KEY_FILE)) {
                String s = Files.readString(LEGACY_KEY_FILE, StandardCharsets.UTF_8).trim();
                return s.isBlank() ? null : s;
            }
        } catch (Exception e) {
            log.warn("读取旧版主密钥文件失败（忽略，按无文件处理）: {}", e.getMessage());
        }
        return null;
    }

    /** 旧版设备派生口令（用户名 + 主机名）：仅用于解密回退与主密钥文件不可用时的降级。 */
    private static String legacyPassphrase() {
        return System.getProperty("user.name", "javaclaw")
                + "@" + getHostName()
                + "#JavaClaw";
    }

    /**
     * 从盐值 + 口令派生 AES-256 密钥
     */
    private static SecretKey deriveKey(byte[] salt, String passphrase) throws Exception {
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 获取主机名（用于密钥派生）
     */
    private static String getHostName() {
        try {
            Path hostnamePath = Path.of("/etc/hostname");
            if (Files.exists(hostnamePath)) {
                return Files.readString(hostnamePath).trim();
            }
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }
}
