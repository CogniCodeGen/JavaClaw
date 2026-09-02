package com.javaclaw.desktop.settings;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.builtin.contracts.SiteContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteCredentialSettingsPresenterTest {
    @Test
    void 创建轮换和永久清除只保留元数据并刷新Site权威视图() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        AtomicInteger authorityRefreshes = new AtomicInteger();
        AtomicReference<SiteCredentialSettingsState> latest = new AtomicReference<>();
        SiteCredentialSettingsPresenter presenter =
                new SiteCredentialSettingsPresenter(gateway, authorityRefreshes::incrementAndGet);
        presenter.subscribe(latest::set);

        presenter.reload();
        assertTrue(latest.get().credentials().isEmpty());

        char[] createdSecret = "site-token-one".toCharArray();
        presenter.create(createdSecret);
        assertCleared(createdSecret);
        CredentialMetadata created = latest.get().selected().orElseThrow();
        assertEquals(
                SiteContracts.SITE_CREDENTIAL_NAMESPACE, created.reference().namespace());
        assertEquals(1, created.revision());
        assertEquals(1, authorityRefreshes.get());

        char[] rotatedSecret = "site-token-two".toCharArray();
        presenter.rotate(rotatedSecret);
        assertCleared(rotatedSecret);
        assertEquals(2, latest.get().selected().orElseThrow().revision());
        assertEquals(2, authorityRefreshes.get());

        presenter.clear();
        assertTrue(latest.get().credentials().isEmpty());
        assertTrue(latest.get().selected().isEmpty());
        assertEquals(3, authorityRefreshes.get());
        assertTrue(latest.get().message().contains("永久清除"));
    }

    private static void assertCleared(char[] secret) {
        for (char value : secret) {
            assertEquals('\0', value);
        }
    }
}
