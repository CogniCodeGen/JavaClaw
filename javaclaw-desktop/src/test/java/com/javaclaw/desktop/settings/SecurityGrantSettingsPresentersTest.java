package com.javaclaw.desktop.settings;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGrantSettingsPresentersTest {
    @Test
    void 私网授权必须先预览确认且撤销写入新版本() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        PrivateNetworkGrantSettingsPresenter presenter = new PrivateNetworkGrantSettingsPresenter(gateway);
        AtomicReference<PrivateNetworkGrantSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();
        assertTrue(latest.get().workspace().isPresent());
        presenter.edit(PrivateNetworkPurpose.MCP, "https://mcp.example.test", "10.0.0.8\nfd00::8", "1");
        assertTrue(latest.get().dirty());
        assertTrue(latest.get().preview().isEmpty());
        presenter.preview();
        assertEquals(
                "https://mcp.example.test",
                latest.get().preview().orElseThrow().origin().toString());

        presenter.confirmCreate();
        assertEquals(1, latest.get().grants().size());
        assertFalse(latest.get().dirty());
        presenter.revoke();
        assertEquals(
                SecurityGrantState.REVOKED,
                latest.get().selected().orElseThrow().state());
        assertEquals(2, latest.get().selected().orElseThrow().revision());
    }

    @Test
    void 无人值守授权冻结Schedule工具目录Schema参数额度和期限() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        UnattendedToolGrantSettingsPresenter presenter = new UnattendedToolGrantSettingsPresenter(gateway);
        AtomicReference<UnattendedToolGrantSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();
        presenter.edit(new UnattendedToolGrantForm(
                "nightly-review",
                "3",
                "builtin.skill",
                "review",
                "4",
                "11",
                "a".repeat(64),
                "{\"query\":\"stable\",\"limit\":3}",
                "query",
                "10",
                "7"));
        presenter.create();

        var status = latest.get().selected().orElseThrow();
        assertEquals("nightly-review", status.grant().scheduleId());
        assertEquals(3, status.grant().scheduleRevision());
        assertEquals(11, status.grant().catalogRevision());
        assertEquals(10, status.remainingUses());
        assertEquals(
                "{\"limit\":3,\"query\":\"stable\"}",
                status.grant().fixedArguments().json());

        presenter.revoke();
        assertEquals(
                SecurityGrantState.REVOKED,
                latest.get().selected().orElseThrow().grant().state());
    }
}
