package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static com.javaclaw.builtin.contracts.BrowserContractFixtures.NOW;
import static com.javaclaw.builtin.contracts.BrowserContractFixtures.ORIGIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserContractsTest {
    @Test
    void 租约冻结来源且NONE和到期边界不保留操作权() {
        Set<URI> source = new HashSet<>(Set.of(ORIGIN));
        var lease = new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.ASSISTANT, " lease ", 1, NOW.plusSeconds(1), source);
        source.clear();
        assertEquals(Set.of(ORIGIN), lease.allowedOrigins());
        assertEquals("lease", lease.leaseId());
        assertTrue(lease.active(NOW));
        assertFalse(lease.active(NOW.plusSeconds(1)));
        assertFalse(lease.active(NOW.plusSeconds(2)));
        assertFalse(new BrowserContracts.AccessLease(
                        BrowserContracts.ControlMode.NONE, "none", 2, NOW.plusSeconds(60), Set.of(ORIGIN))
                .active(NOW));
        assertTrue(new BrowserContracts.AccessLease(
                        BrowserContracts.ControlMode.HUMAN, "human", 3, NOW.plusSeconds(60), Set.of(ORIGIN))
                .active(NOW));
        assertThrows(
                UnsupportedOperationException.class,
                () -> lease.allowedOrigins().clear());
    }

    @Test
    void 来源只允许精确Authority并限制代次和数量() {
        for (String bad : List.of(
                "https://example.com/path",
                "https://example.com?x=1",
                "https://example.com/#x",
                "https://user:secret@example.com",
                "file:///tmp/private",
                "https:///no-host")) {
            assertThrows(IllegalArgumentException.class, () -> lease(Set.of(URI.create(bad)), 1), bad);
        }
        assertEquals(
                1,
                lease(Set.of(URI.create("https://example.com/")), 1)
                        .allowedOrigins()
                        .size());
        assertThrows(IllegalArgumentException.class, () -> lease(Set.of(ORIGIN), 0));
        Set<URI> maximum = IntStream.range(0, 128)
                .mapToObj(index -> URI.create("https://host" + index + ".example"))
                .collect(Collectors.toSet());
        assertEquals(128, lease(maximum, 1).allowedOrigins().size());
        maximum.add(ORIGIN);
        assertThrows(IllegalArgumentException.class, () -> lease(maximum, 1));
    }

    @Test
    void 浏览器所有权必须完整且启动身份和地址不能伪装成本地文件或带凭据地址() {
        var owner = BrowserContractFixtures.owner();
        var task = new BrowserContracts.OpenTask(
                BrowserContractFixtures.SESSION, owner, ORIGIN.resolve("/page?q=1"), lease(Set.of(ORIGIN), 1));
        assertEquals(owner, task.owner());
        assertEquals(2, owner.account().orElseThrow().securityRevision());
        assertThrows(
                NullPointerException.class, () -> new BrowserContracts.Owner(null, owner.threadId(), Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserContracts.Owner(owner.workspaceId(), null, Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserContracts.Owner(owner.workspaceId(), owner.threadId(), null));
        assertThrows(IllegalArgumentException.class, () -> new BrowserContracts.AccountBinding("work", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserContracts.OpenTask("invalid", owner, ORIGIN, BrowserContractFixtures.lease()));
        for (String bad : List.of(
                "file:///tmp/secret",
                "https:///missing",
                "https://user:secret@example.com",
                "https://example.com/" + "x".repeat(8192))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new BrowserContracts.OpenTask(
                            BrowserContractFixtures.SESSION, owner, URI.create(bad), BrowserContractFixtures.lease()));
        }
    }

    @Test
    void 图片坐标不接受负数无穷和NaN且拖动不能缺少端点() {
        for (double bad : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new BrowserContracts.Point(bad, 0));
            assertThrows(IllegalArgumentException.class, () -> new BrowserContracts.Point(0, bad));
        }
        var from = new BrowserContracts.Point(0, 0);
        var to = new BrowserContracts.Point(2559, 1799);
        assertEquals(to, new BrowserContracts.Drag(from, to).to());
        assertThrows(NullPointerException.class, () -> new BrowserContracts.Drag(null, to));
        assertThrows(NullPointerException.class, () -> new BrowserContracts.Drag(from, null));
    }

    @Test
    void 元素和截图引用长度有界且无参数动作不携带上传内容() {
        assertEquals(new BrowserContracts.Target("", "", ""), BrowserContracts.Target.current());
        assertEquals(
                100,
                new BrowserContracts.Target("p".repeat(100), "r", "f").pageId().length());
        assertThrows(IllegalArgumentException.class, () -> new BrowserContracts.Target("p".repeat(101), "", ""));
        assertThrows(IllegalArgumentException.class, () -> new BrowserContracts.Target("", "r".repeat(101), ""));
        assertThrows(IllegalArgumentException.class, () -> new BrowserContracts.Target("", "", "f".repeat(101)));
        var action = BrowserContracts.Action.simple(BrowserContracts.Operation.RELOAD);
        assertEquals(BrowserContracts.Target.current(), action.target());
        assertTrue(action.input().file().isEmpty());
        assertTrue(action.input().point().isEmpty());
        assertTrue(action.input().drag().isEmpty());
        assertEquals("", action.input().value());
        assertThrows(
                NullPointerException.class, () -> new BrowserContracts.Action(null, action.target(), action.input()));
    }

    @Test
    void 上传只携带安全文件名与有界输入不能带宿主路径() {
        for (String bad : List.of("../secret", "folder/file", "C:\\secret", "a\nb", " ", "x".repeat(201))) {
            assertThrows(IllegalArgumentException.class, () -> new BrowserContracts.FileSpec(bad, "text/plain"));
        }
        assertEquals("report.txt", new BrowserContracts.FileSpec(" report.txt ", " text/plain ").fileName());
        assertThrows(IllegalArgumentException.class, () -> new BrowserContracts.FileSpec("file", "x".repeat(101)));
        assertEquals(
                16_384,
                BrowserContracts.ActionInput.text("x".repeat(16_384)).value().length());
        assertThrows(IllegalArgumentException.class, () -> BrowserContracts.ActionInput.text("x".repeat(16_385)));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserContracts.ActionInput("", null, Optional.empty(), Optional.empty()));
        var file = new BrowserContracts.FileSpec("upload.txt", "text/plain");
        var upload = new BrowserContracts.ActionInput("", Optional.empty(), Optional.empty(), Optional.of(file));
        assertEquals(file, upload.file().orElseThrow());
    }

    @Test
    void 标签和页面观察冻结有序集合并拒绝空元素() {
        var original = BrowserContractFixtures.session();
        List<BrowserContracts.Tab> tabs = new ArrayList<>(original.tabs());
        var view = new BrowserContracts.SessionView(
                original.sessionId(), original.owner(), original.state(), original.lease(), tabs);
        tabs.clear();
        assertEquals(1, view.tabs().size());
        assertTrue(view.tabs().getFirst().active());
        assertThrows(UnsupportedOperationException.class, () -> view.tabs().clear());
        assertThrows(
                NullPointerException.class,
                () -> new BrowserContracts.SessionView(
                        original.sessionId(),
                        original.owner(),
                        original.state(),
                        original.lease(),
                        java.util.Arrays.asList((BrowserContracts.Tab) null)));
        var page = BrowserContractFixtures.page();
        List<BrowserContracts.Element> elements = new ArrayList<>(page.elements());
        List<BrowserContracts.DownloadInfo> downloads = new ArrayList<>(page.downloads());
        var snapshot = new BrowserContracts.PageSnapshot(
                page.pageId(), page.uri(), page.title(), page.text(), elements, downloads);
        elements.clear();
        downloads.clear();
        assertEquals(1, snapshot.elements().size());
        assertEquals(1, snapshot.downloads().size());
        assertThrows(
                UnsupportedOperationException.class, () -> snapshot.downloads().clear());
    }

    @Test
    void 网络DTO拒绝危险方法与非页面地址且复制每个请求头列表() {
        var values = new ArrayList<>(List.of("value"));
        Map<String, List<String>> headers = new HashMap<>(Map.of("Accept", values));
        var request = new BrowserContracts.NetworkRequest(ORIGIN.resolve("/api?q=1"), " post ", headers);
        values.clear();
        headers.clear();
        assertEquals("POST", request.method());
        assertEquals(List.of("value"), request.headers().get("Accept"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.headers().get("Accept").clear());
        assertThrows(
                IllegalArgumentException.class, () -> new BrowserContracts.NetworkRequest(ORIGIN, "CONNECT", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserContracts.NetworkRequest(ORIGIN, "GET", Map.of(" ", List.of())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserContracts.NetworkRequest(URI.create("file:///tmp/secret"), "GET", Map.of()));
        Map<String, List<String>> excessive =
                IntStream.range(0, 129).boxed().collect(Collectors.toMap(index -> "x-" + index, index -> List.of("v")));
        assertThrows(
                IllegalArgumentException.class, () -> new BrowserContracts.NetworkRequest(ORIGIN, "GET", excessive));
    }

    @Test
    void 登录捕获目标必须具有完整的页面与两字段引用() {
        var target = new BrowserContracts.CredentialsTarget("page", "user", "password");
        assertEquals("user", target.usernameRef());
        assertThrows(
                IllegalArgumentException.class, () -> new BrowserContracts.CredentialsTarget("", "user", "password"));
        assertThrows(
                IllegalArgumentException.class, () -> new BrowserContracts.CredentialsTarget("page", "", "password"));
        assertThrows(IllegalArgumentException.class, () -> new BrowserContracts.CredentialsTarget("page", "user", " "));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserContracts.CredentialsTarget("page", "x".repeat(101), "password"));
    }

    private static BrowserContracts.AccessLease lease(Set<URI> origins, long generation) {
        return new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.ASSISTANT, "lease", generation, NOW.plusSeconds(60), origins);
    }
}
