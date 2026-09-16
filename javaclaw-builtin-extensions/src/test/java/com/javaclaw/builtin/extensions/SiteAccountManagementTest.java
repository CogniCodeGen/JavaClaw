package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SiteAccountManagementTest {
    @Test
    void 账号工具与页面只透传精确网站和脱敏状态() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SiteExtension());
        AtomicReference<SiteAccountContracts.ServiceRequest> task = new AtomicReference<>();
        support.service = (caller, service, payload) -> {
            assertEquals(SiteAccountContracts.SERVICE, service);
            task.set(support.payloads.decode(payload, SiteAccountContracts.ServiceRequest.class));
            return support.payloads.encode(new SiteAccountContracts.AccountList(List.of(account())));
        };
        var result = started.tool(
                "site.accounts.tool",
                support.request("accounts", new SiteAccountContracts.ListRequest("site"), Optional.empty(), 0));
        assertEquals("account/list", task.get().operation());
        assertFalse(result.payload().json().contains("namespace"));
        assertFalse(result.payload().json().contains("reference"));
        assertEquals(
                new SiteAccountContracts.ListRequest("site"),
                support.payloads.decode(task.get().payload(), SiteAccountContracts.ListRequest.class));
        ViewQueryResult view = support.decode(
                started.query(support.request(
                        "account/view",
                        new ViewQueryRequest("accounts", Map.of("siteId", "site"), "", 100, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        assertEquals(1, view.rows().size());
        assertEquals("accounts", view.dataSourceId());
    }

    @Test
    void 账号命令透传版本幂等身份而秘密操作不注册普通扩展通道() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SiteExtension());
        support.service = (caller, service, payload) -> {
            SiteAccountContracts.ServiceRequest task =
                    support.payloads.decode(payload, SiteAccountContracts.ServiceRequest.class);
            assertEquals("account/logout", task.operation());
            assertEquals("logout-once", task.idempotencyKey());
            assertEquals(4, task.expectedRevision());
            return support.payloads.encode(account());
        };
        var result = started.command(support.request(
                "account/logout",
                new SiteAccountContracts.Selection("site", "account"),
                Optional.of("logout-once"),
                4));
        assertEquals(account().revision(), result.revision());
        assertThrows(
                RuntimeException.class,
                () -> started.command(support.request(
                        "account/credential/set",
                        Map.of("username", "forbidden", "password", "forbidden"),
                        Optional.of("bad"),
                        0)));
    }

    private static SiteAccountContracts.AccountProjection account() {
        return new SiteAccountContracts.AccountProjection(
                "account", "site", 5, 3, 2, "工作", true, true, true, true, NOW);
    }
}
