package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.protocol.CanonicalJson;

import static com.javaclaw.builtin.contracts.BrowserContractFixtures.NOW;
import static com.javaclaw.builtin.contracts.BrowserContractFixtures.ORIGIN;
import static com.javaclaw.builtin.contracts.BrowserContractFixtures.SESSION;
import static com.javaclaw.builtin.contracts.BrowserContractFixtures.WORKSPACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteRegistrationContractsTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 地址只允许有界HTTPS且显示投影删除令牌并保留路径转义() {
        URI input = URI.create("https://example.com/a%2Fb/%3F?q=token#session-secret");
        var begin = new SiteRegistrationContracts.BeginRequest(input);
        assertEquals(begin, json.decode(json.encode(begin), SiteRegistrationContracts.BeginRequest.class));
        assertEquals(URI.create("https://example.com/a%2Fb/%3F"), SiteRegistrationContracts.displayUri(input));
        for (String invalid : List.of(
                "http://example.com",
                "file:///private/data",
                "/relative",
                "https://user:pass@example.com",
                "https://example.com:0",
                "https://example.com:65536",
                "https://example.com/" + "a".repeat(4096))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SiteRegistrationContracts.BeginRequest(URI.create(invalid)));
        }
    }

    @Test
    void 新增来源必须精确且旧代次和无效UUID不能进入宿主() {
        var request = new SiteRegistrationContracts.OriginRequest(SESSION, 2, ORIGIN);
        assertEquals(request, json.decode(json.encode(request), SiteRegistrationContracts.OriginRequest.class));
        for (String path : List.of("/", "/login", "?token=secret", "#fragment")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SiteRegistrationContracts.OriginRequest(SESSION, 2, URI.create(ORIGIN + path)));
        }
        assertThrows(
                IllegalArgumentException.class, () -> new SiteRegistrationContracts.OriginRequest(SESSION, 0, ORIGIN));
        assertThrows(
                IllegalArgumentException.class, () -> new SiteRegistrationContracts.SessionRequest("not-a-session"));
        assertEquals(SESSION, new SiteRegistrationContracts.SessionRequest(SESSION).sessionId());
    }

    @Test
    void 完成保存只传网站名候选身份与两个确认版本() {
        var complete = new SiteRegistrationContracts.CompleteRequest(SESSION, 2, 3, Optional.of("candidate"), " 网站 ");
        assertEquals("网站", complete.name());
        assertEquals(complete, json.decode(json.encode(complete), SiteRegistrationContracts.CompleteRequest.class));
        assertFalse(json.encode(complete).json().contains("password"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.CompleteRequest(SESSION, 0, 3, Optional.empty(), "网站"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.CompleteRequest(SESSION, 2, 0, Optional.empty(), "网站"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.CompleteRequest(SESSION, 2, 3, Optional.of(" "), "网站"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.CompleteRequest(SESSION, 2, 3, Optional.empty(), "x".repeat(201)));
    }

    @Test
    void 页面快照禁止查询令牌重复候选及无限候选列表() {
        var candidate = candidate("one");
        List<SiteRegistrationContracts.CredentialCandidate> source = new ArrayList<>(List.of(candidate));
        var page = new SiteRegistrationContracts.Page(1, Optional.of(ORIGIN), "网站", source);
        source.clear();
        assertEquals(List.of(candidate), page.candidates());
        assertEquals(page, json.decode(json.encode(page), SiteRegistrationContracts.Page.class));
        assertThrows(
                UnsupportedOperationException.class, () -> page.candidates().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.Page(-1, Optional.empty(), "", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.Page(
                        1, Optional.of(URI.create(ORIGIN + "?token=secret")), "", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.Page(
                        1, Optional.of(URI.create(ORIGIN + "#secret")), "", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.Page(1, Optional.of(ORIGIN), "", List.of(candidate, candidate)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.Page(
                        1,
                        Optional.of(ORIGIN),
                        "",
                        IntStream.range(0, 21)
                                .mapToObj(i -> candidate("id" + i))
                                .toList()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.Page(1, Optional.empty(), "x".repeat(1001), List.of()));
    }

    @Test
    void 来源授权集合不可变且待授权来源并不会进入允许集合() {
        Set<URI> source = new HashSet<>(Set.of(ORIGIN));
        URI pending = URI.create("https://login.example.com");
        var access = new SiteRegistrationContracts.Access(1, source, Set.of(pending), NOW.plusSeconds(600));
        source.clear();
        assertEquals(Set.of(ORIGIN), access.allowedOrigins());
        assertFalse(access.allowedOrigins().contains(pending));
        assertEquals(access, json.decode(json.encode(access), SiteRegistrationContracts.Access.class));
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.pendingOrigins().clear());
        Set<URI> tooMany = IntStream.range(0, 129)
                .mapToObj(i -> URI.create("https://host" + i + ".example.com"))
                .collect(Collectors.toSet());
        assertThrows(
                IllegalArgumentException.class, () -> new SiteRegistrationContracts.Access(1, tooMany, Set.of(), NOW));
        assertThrows(
                IllegalArgumentException.class, () -> new SiteRegistrationContracts.Access(1, Set.of(), tooMany, NOW));
    }

    @Test
    void 临时Worker必须人工控制且不能自行宣称站点已经提交() {
        URI uri = URI.create(ORIGIN + "/app#route");
        var task = new SiteRegistrationContracts.WorkerTask(
                SESSION, WORKSPACE, uri, lease(BrowserContracts.ControlMode.HUMAN));
        assertEquals(task, json.decode(json.encode(task), SiteRegistrationContracts.WorkerTask.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.WorkerTask(
                        SESSION, WORKSPACE, uri, lease(BrowserContracts.ControlMode.ASSISTANT)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.WorkerTask(
                        SESSION, WORKSPACE, URI.create("https://other.example.com"), task.lease()));
        var status = new SiteRegistrationContracts.WorkerStatus(
                SESSION, SiteRegistrationContracts.State.ACTIVE, access(), page());
        assertEquals(status, json.decode(json.encode(status), SiteRegistrationContracts.WorkerStatus.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.WorkerStatus(
                        SESSION, SiteRegistrationContracts.State.COMPLETED, access(), page()));
    }

    @Test
    void 持久成功结果必须与终态一致且不包含密文引用() {
        var completed = new SiteRegistrationContracts.Completed("site", "account", ORIGIN);
        var session = new SiteRegistrationContracts.Session(
                SESSION, SiteRegistrationContracts.State.COMPLETED, access(), page(), Optional.of(completed));
        assertEquals(session, json.decode(json.encode(session), SiteRegistrationContracts.Session.class));
        assertFalse(json.encode(session).json().contains("reference"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.Session(
                        SESSION, SiteRegistrationContracts.State.ACTIVE, access(), page(), Optional.of(completed)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.Session(
                        SESSION, SiteRegistrationContracts.State.COMPLETED, access(), page(), Optional.empty()));
        for (var state : SiteRegistrationContracts.State.values()) {
            if (state != SiteRegistrationContracts.State.COMPLETED) {
                assertTrue(new SiteRegistrationContracts.Session(SESSION, state, access(), page(), Optional.empty())
                        .completed()
                        .isEmpty());
            }
        }
    }

    @Test
    void 服务身份往返保留查询和写入差别并限制无界文本() {
        var request = new SiteRegistrationContracts.ServiceRequest(
                "registration.begin",
                json.encode(new SiteRegistrationContracts.BeginRequest(ORIGIN)),
                Optional.of("key"));
        assertEquals(request, json.decode(json.encode(request), SiteRegistrationContracts.ServiceRequest.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.ServiceRequest(" ", request.payload(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.ServiceRequest(
                        "registration.begin", request.payload(), Optional.of("x".repeat(201))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.CredentialCandidate("id", ORIGIN, "x".repeat(301)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SiteRegistrationContracts.Completed("s".repeat(201), "account", ORIGIN));
    }

    private static SiteRegistrationContracts.CredentialCandidate candidate(String id) {
        return new SiteRegistrationContracts.CredentialCandidate(id, ORIGIN, "登录表单");
    }

    private static SiteRegistrationContracts.Access access() {
        return new SiteRegistrationContracts.Access(1, Set.of(ORIGIN), Set.of(), NOW.plusSeconds(600));
    }

    private static SiteRegistrationContracts.Page page() {
        return new SiteRegistrationContracts.Page(1, Optional.of(ORIGIN), "网站", List.of(candidate("one")));
    }

    private static BrowserContracts.AccessLease lease(BrowserContracts.ControlMode mode) {
        return new BrowserContracts.AccessLease(mode, "lease", 1, NOW.plusSeconds(600), Set.of(ORIGIN));
    }
}
