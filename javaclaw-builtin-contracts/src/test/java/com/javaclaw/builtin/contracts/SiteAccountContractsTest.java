package com.javaclaw.builtin.contracts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteAccountContractsTest {
    private final CanonicalJson json = new CanonicalJson();
    private static final SiteAccountContracts.Selection SELECTION = new SiteAccountContracts.Selection("site", "work");

    @Test
    void 账号所有权和用户选择不接受空或越界标识() {
        var scope = scope();
        assertEquals(BrowserContractFixtures.WORKSPACE, scope.workspaceId());
        assertEquals(scope, json.decode(json.encode(scope), SiteAccountContracts.AccountScope.class));
        assertEquals("site", new SiteAccountContracts.Selection(" site ", " work ").siteId());
        assertEquals(
                100,
                new SiteAccountContracts.Selection("s".repeat(100), "a")
                        .siteId()
                        .length());
        assertThrows(NullPointerException.class, () -> new SiteAccountContracts.AccountScope(null, "site", "work"));
        assertThrows(IllegalArgumentException.class, () -> new SiteAccountContracts.Selection(" ", "work"));
        assertThrows(IllegalArgumentException.class, () -> new SiteAccountContracts.Selection("site", "a".repeat(101)));
        assertThrows(IllegalArgumentException.class, () -> new SiteAccountContracts.ListRequest(""));
    }

    @Test
    void 密码确认只引用账号与安全版本而不允许缺失确认代次() {
        var request = new SiteAccountContracts.CredentialRequest(SELECTION, 2, 3);
        assertEquals(request, json.decode(json.encode(request), SiteAccountContracts.CredentialRequest.class));
        assertEquals(
                Set.of("selection", "expectedSecurityRevision", "expectedSiteAuthorityRevision"),
                java.util.Arrays.stream(SiteAccountContracts.CredentialRequest.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(Collectors.toSet()));
        assertThrows(IllegalArgumentException.class, () -> new SiteAccountContracts.CredentialRequest(SELECTION, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SiteAccountContracts.CredentialRequest(SELECTION, 1, 0));
        assertThrows(NullPointerException.class, () -> new SiteAccountContracts.CredentialRequest(null, 1, 1));
    }

    @Test
    void 账号名称与管理请求往返且保留明确启用状态() {
        var create = new SiteAccountContracts.CreateRequest("site", " 工作账号 ");
        assertEquals("工作账号", create.name());
        assertEquals(create, json.decode(json.encode(create), SiteAccountContracts.CreateRequest.class));
        var update = new SiteAccountContracts.UpdateRequest(SELECTION, "个人", false);
        assertEquals(update, json.decode(json.encode(update), SiteAccountContracts.UpdateRequest.class));
        assertFalse(update.enabled());
        assertEquals(
                new SiteAccountContracts.ListRequest("site"),
                json.decode(
                        json.encode(new SiteAccountContracts.ListRequest("site")),
                        SiteAccountContracts.ListRequest.class));
        assertThrows(IllegalArgumentException.class, () -> new SiteAccountContracts.CreateRequest("site", " "));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteAccountContracts.UpdateRequest(SELECTION, "x".repeat(201), true));
        var service = new SiteAccountContracts.ServiceRequest("account/create", json.encode(create), "key", 0);
        assertEquals(service, json.decode(json.encode(service), SiteAccountContracts.ServiceRequest.class));
        assertThrows(
                NullPointerException.class,
                () -> new SiteAccountContracts.ServiceRequest("account/create", null, "key", 0));
    }

    @Test
    void 账号投影把管理安全和登录态版本分别保留且列表不可被外部修改() {
        var account = projection(1, 2, 0);
        assertEquals(account, json.decode(json.encode(account), SiteAccountContracts.AccountProjection.class));
        assertTrue(account.passwordConfigured());
        assertFalse(account.loginStateConfigured());
        List<SiteAccountContracts.AccountProjection> source = new ArrayList<>(List.of(account));
        var listed = new SiteAccountContracts.AccountList(source);
        source.clear();
        assertEquals(List.of(account), listed.accounts());
        assertEquals(listed, json.decode(json.encode(listed), SiteAccountContracts.AccountList.class));
        assertThrows(
                UnsupportedOperationException.class, () -> listed.accounts().clear());
        assertThrows(IllegalArgumentException.class, () -> projection(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> projection(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> projection(1, 1, -1));
        assertFalse(json.encode(account).json().contains("\"password\":"));
        assertFalse(json.encode(account).json().contains("\"storageState\":"));
    }

    @Test
    void 私有保存租约严格区分账号安全版本状态版本网站版本与会话代次() {
        var lease = new SiteAccountContracts.StateLease(scope(), "session", 2, 0, 3, 4);
        assertEquals(lease, json.decode(json.encode(lease), SiteAccountContracts.StateLease.class));
        assertEquals(0, lease.stateRevision());
        assertEquals(3, lease.siteAuthorityRevision());
        assertEquals(4, lease.generation());
        assertThrows(
                NullPointerException.class, () -> new SiteAccountContracts.StateLease(null, "session", 2, 0, 3, 4));
        assertThrows(
                IllegalArgumentException.class, () -> new SiteAccountContracts.StateLease(scope(), " ", 2, 0, 3, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteAccountContracts.StateLease(scope(), "session", 0, 0, 3, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteAccountContracts.StateLease(scope(), "session", 2, -1, 3, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteAccountContracts.StateLease(scope(), "session", 2, 0, 0, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteAccountContracts.StateLease(scope(), "session", 2, 0, 3, 0));
    }

    @Test
    void 反序列化仍执行安全版本边界并拒绝被篡改的确认() {
        var valid = json.encode(new SiteAccountContracts.CredentialRequest(SELECTION, 2, 3));
        var invalid = new CanonicalPayload(
                valid.json().replace("\"expectedSecurityRevision\":2", "\"expectedSecurityRevision\":0"));
        assertThrows(RuntimeException.class, () -> json.decode(invalid, SiteAccountContracts.CredentialRequest.class));
    }

    @Test
    void 两份秘密保存时间独立往返且不使用账号最近修改时间() {
        var account = new SiteAccountContracts.AccountProjection(
                "work", "site", 4, 2, 3, "重命名", true, true, true, true, BrowserContractFixtures.NOW.plusSeconds(500));
        var times = new SiteAccountContracts.AccountSavedTimes(
                Optional.of(BrowserContractFixtures.NOW), Optional.of(BrowserContractFixtures.NOW.plusSeconds(10)));
        Map<String, SiteAccountContracts.AccountSavedTimes> source = new HashMap<>(Map.of("work", times));
        var listed = new SiteAccountContracts.AccountList(List.of(account), source);
        source.clear();
        assertEquals(listed, json.decode(json.encode(listed), SiteAccountContracts.AccountList.class));
        assertEquals(
                Optional.of(BrowserContractFixtures.NOW),
                listed.savedTimes().get("work").passwordSavedAt());
        assertEquals(
                Optional.of(BrowserContractFixtures.NOW.plusSeconds(10)),
                listed.savedTimes().get("work").loginStateSavedAt());
        assertThrows(
                UnsupportedOperationException.class, () -> listed.savedTimes().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteAccountContracts.AccountList(List.of(account), Map.of("other", times)));
    }

    @Test
    void 旧账号列表缺少保存时间字段仍解码为空映射且新时间不能用null代替Optional() {
        var account = projection(1, 2, 0);
        var legacy = json.encode(Map.of("accounts", List.of(account)));
        assertTrue(json.decode(legacy, SiteAccountContracts.AccountList.class)
                .savedTimes()
                .isEmpty());
        assertEquals(
                new SiteAccountContracts.AccountList(List.of(account)),
                new SiteAccountContracts.AccountList(List.of(account), null));
        assertThrows(
                NullPointerException.class, () -> new SiteAccountContracts.AccountSavedTimes(null, Optional.empty()));
        assertThrows(
                NullPointerException.class, () -> new SiteAccountContracts.AccountSavedTimes(Optional.empty(), null));
    }

    private static SiteAccountContracts.AccountScope scope() {
        return new SiteAccountContracts.AccountScope(BrowserContractFixtures.WORKSPACE, "site", "work");
    }

    private static SiteAccountContracts.AccountProjection projection(long revision, long security, long state) {
        return new SiteAccountContracts.AccountProjection(
                "work", "site", revision, security, state, "工作", true, true, true, false, BrowserContractFixtures.NOW);
    }
}
