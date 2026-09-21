package com.javaclaw.server.rpc;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteRegistrationEventsTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final WorkspaceId WORKSPACE = WorkspaceId.random();
    private static final URI ORIGIN = URI.create("https://example.com");

    @Test
    void 每个新网站独立广播身份且不携带页面或密码候选() {
        var call = call(BuiltinExtensionIds.SITE, "registration.complete");
        var first = SiteRegistrationEvents.completed(call, response("site-one"), JSON)
                .orElseThrow();
        var second = SiteRegistrationEvents.completed(call, response("site-two"), JSON)
                .orElseThrow();
        assertNotEquals(first.resourceId(), second.resourceId());
        assertEquals("site", first.scope());
        assertEquals(WORKSPACE, first.workspaceId());
        assertEquals(1, first.revision());
        assertEquals("registration.complete", first.operation());
        assertFalse(JSON.encode(first).json().contains("private-page-title"));
        assertFalse(JSON.encode(first).json().contains("candidate"));
    }

    @Test
    void 临时会话不使网站列表失效而已提交回执恢复仍指向同一网站() {
        for (String operation : List.of("registration.begin", "registration.origin", "registration.cancel")) {
            var call = call(BuiltinExtensionIds.SITE, operation);
            assertTrue(SiteRegistrationEvents.handles(call));
            assertTrue(
                    SiteRegistrationEvents.completed(call, response(null), JSON).isEmpty());
            assertEquals(
                    "site-one",
                    SiteRegistrationEvents.completed(call, response("site-one"), JSON)
                            .orElseThrow()
                            .resourceId());
        }
        assertFalse(SiteRegistrationEvents.handles(call("third.party", "registration.complete")));
        assertFalse(SiteRegistrationEvents.handles(call(BuiltinExtensionIds.SITE, "account/create")));
        assertFalse(SiteRegistrationEvents.handles(call(BuiltinExtensionIds.SITE, "registration.status")));
    }

    private static ExtensionRpcContracts.CallPayload call(String extension, String operation) {
        return new ExtensionRpcContracts.CallPayload(
                extension, WORKSPACE, Optional.empty(), Optional.empty(), operation, new CanonicalPayload("{}"));
    }

    private static ExtensionResponse response(String site) {
        var state = site == null ? SiteRegistrationContracts.State.ACTIVE : SiteRegistrationContracts.State.COMPLETED;
        var completed = Optional.ofNullable(site)
                .map(value -> new SiteRegistrationContracts.Completed(value, "account", ORIGIN));
        var session = new SiteRegistrationContracts.Session(
                "36778252-20a2-432d-a07a-e76b1866a52c",
                state,
                new SiteRegistrationContracts.Access(
                        1, Set.of(ORIGIN), Set.of(), Instant.parse("2026-09-20T05:00:00Z")),
                new SiteRegistrationContracts.Page(
                        1,
                        Optional.of(ORIGIN),
                        "private-page-title",
                        List.of(new SiteRegistrationContracts.CredentialCandidate("candidate", ORIGIN, "登录表单"))),
                completed);
        return new ExtensionResponse(JSON.encode(session), site == null ? 0 : 1);
    }
}
