package com.javaclaw.config;

import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialEncryptorTest {

    @TempDir
    Path tempDirectory;

    private AnnotationConfigApplicationContext context;
    private CredentialCipher credentials;

    @BeforeEach
    void createCipher() {
        context = ApplicationContexts.createRoot(new DataRoot(tempDirectory.resolve("data-v3")));
        credentials = context.getBean(CredentialCipher.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void encryptionRoundTripUsesDatabaseMasterKey() {
        String plain = "s3cret-密码!@#";
        String encrypted = credentials.encrypt(plain);

        assertTrue(credentials.isEncrypted(encrypted));
        assertNotEquals(plain, encrypted);
        assertEquals(plain, credentials.decrypt(encrypted));
    }

    @Test
    void plainAndEmptyValuesPassThrough() {
        assertEquals("plain-password", credentials.decrypt("plain-password"));
        assertNull(credentials.decrypt(null));
        assertEquals("", credentials.decrypt(""));
        assertNull(credentials.encrypt(null));
        assertEquals("", credentials.encrypt(""));
    }

    @Test
    void encryptionIsIdempotentForCipherText() {
        String encrypted = credentials.encrypt("abc");
        assertEquals(encrypted, credentials.encrypt(encrypted));
    }

    @Test
    void independentServiceInstancesAdoptTheSamePersistedMasterKey() {
        CredentialCipher second = new CredentialEncryptor(context.getBean(JdbcTemplate.class));
        String encrypted = credentials.encrypt("shared-secret");

        assertEquals("shared-secret", second.decrypt(encrypted));
        Integer keyCount = context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT COUNT(*) FROM app_state WHERE state_key = 'credential.master.key'",
                Integer.class);
        assertEquals(1, keyCount);
    }

    @Test
    void unreadableCipherTextIsPreservedInsteadOfDestroyed() {
        byte[] junk = new byte[60];
        new java.util.Random(42).nextBytes(junk);
        String unreadable = "ENC(" + java.util.Base64.getEncoder().encodeToString(junk) + ")";

        String preserved = credentials.decrypt(unreadable);
        assertEquals(unreadable, preserved);
        assertEquals(unreadable, credentials.encrypt(preserved));
    }
}
