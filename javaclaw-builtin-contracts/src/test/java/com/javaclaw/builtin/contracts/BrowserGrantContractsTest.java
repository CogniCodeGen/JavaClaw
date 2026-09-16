package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;

import static com.javaclaw.builtin.contracts.BrowserContractFixtures.NOW;
import static com.javaclaw.builtin.contracts.BrowserContractFixtures.ORIGIN;
import static com.javaclaw.builtin.contracts.BrowserContractFixtures.SESSION;
import static com.javaclaw.builtin.contracts.BrowserContractFixtures.THREAD;
import static com.javaclaw.builtin.contracts.BrowserContractFixtures.WORKSPACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserGrantContractsTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 预览使用规范HTTPS来源和完整摘要且真实序列化保留确认边界() {
        var preview = new BrowserGrantContracts.Preview(
                WORKSPACE, THREAD, URI.create("https://EXAMPLE.COM:443/"), NOW.plusSeconds(60), "a".repeat(64));
        assertEquals(ORIGIN, preview.origin());
        assertEquals(preview, json.decode(json.encode(preview), BrowserGrantContracts.Preview.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserGrantContracts.Preview(WORKSPACE, THREAD, ORIGIN, NOW, "A".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserGrantContracts.Preview(WORKSPACE, THREAD, ORIGIN, NOW, "a".repeat(63)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserGrantContracts.Preview(WORKSPACE, THREAD, ORIGIN, NOW, null));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserGrantContracts.Preview(null, THREAD, ORIGIN, NOW, "a".repeat(64)));
    }

    @Test
    void 浏览器授权不接受HTTP路径查询片段或带凭据来源() {
        for (String value : List.of(
                "http://example.com",
                "https://example.com/path",
                "https://example.com?x=1",
                "https://example.com#fragment",
                "https://user:secret@example.com",
                "https:///missing")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BrowserGrantContracts.normalizeOrigin(URI.create(value)),
                    value);
        }
        assertEquals(
                URI.create("https://example.com:444"),
                BrowserGrantContracts.normalizeOrigin(URI.create("https://EXAMPLE.com:444/")));
    }

    @Test
    void 授权和引用必须具有UUID与正数安全版本() {
        var active =
                new BrowserGrantContracts.Grant(SESSION, 1, SecurityGrantState.ACTIVE, WORKSPACE, THREAD, ORIGIN, NOW);
        var revoked = new BrowserGrantContracts.Grant(
                SESSION, 2, SecurityGrantState.REVOKED, WORKSPACE, THREAD, ORIGIN, NOW.plusSeconds(1));
        assertEquals(active, json.decode(json.encode(active), BrowserGrantContracts.Grant.class));
        assertEquals(SecurityGrantState.REVOKED, revoked.state());
        var ref = new BrowserGrantContracts.GrantRef(SESSION, 2);
        assertEquals(ref, json.decode(json.encode(ref), BrowserGrantContracts.GrantRef.class));
        assertThrows(IllegalArgumentException.class, () -> new BrowserGrantContracts.GrantRef("invalid", 1));
        assertThrows(IllegalArgumentException.class, () -> new BrowserGrantContracts.GrantRef(SESSION, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserGrantContracts.Grant(
                        SESSION, 0, SecurityGrantState.ACTIVE, WORKSPACE, THREAD, ORIGIN, NOW));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserGrantContracts.Grant(SESSION, 1, null, WORKSPACE, THREAD, ORIGIN, NOW));
    }

    @Test
    void 授权快照复制有界映射并保留逐Origin版本引用() {
        var ref = new BrowserGrantContracts.GrantRef(SESSION, 2);
        Map<URI, BrowserGrantContracts.GrantRef> original = new HashMap<>(Map.of(ORIGIN, ref));
        var snapshot = snapshot(original);
        original.clear();
        assertEquals(Map.of(ORIGIN, ref), snapshot.grants());
        assertTrue(snapshot.origins().contains(ORIGIN));
        assertEquals(snapshot, json.decode(json.encode(snapshot), BrowserGrantContracts.Snapshot.class));
        assertThrows(
                UnsupportedOperationException.class, () -> snapshot.grants().clear());
        Map<URI, BrowserGrantContracts.GrantRef> tooMany = IntStream.range(0, 129)
                .boxed()
                .collect(Collectors.toMap(index -> URI.create("https://host" + index + ".example"), index -> ref));
        assertThrows(IllegalArgumentException.class, () -> snapshot(tooMany));
        assertThrows(IllegalArgumentException.class, () -> snapshot(Map.of(ORIGIN.resolve("/path"), ref)));
    }

    @Test
    void 授权列表使用对象包装避免规范载荷拒绝裸JSON数组() {
        var grant =
                new BrowserGrantContracts.Grant(SESSION, 1, SecurityGrantState.ACTIVE, WORKSPACE, THREAD, ORIGIN, NOW);
        var source = new ArrayList<>(List.of(grant));
        var list = new BrowserGrantContracts.GrantList(source);
        source.clear();
        var payload = json.encode(list);
        assertTrue(payload.json().startsWith("{"));
        assertEquals(list, json.decode(payload, BrowserGrantContracts.GrantList.class));
        assertEquals(List.of(grant), list.grants());
        assertThrows(UnsupportedOperationException.class, () -> list.grants().clear());
    }

    private static BrowserGrantContracts.Snapshot snapshot(Map<URI, BrowserGrantContracts.GrantRef> grants) {
        return new BrowserGrantContracts.Snapshot(SESSION, WORKSPACE, THREAD, TurnId.random(), grants, NOW);
    }
}
