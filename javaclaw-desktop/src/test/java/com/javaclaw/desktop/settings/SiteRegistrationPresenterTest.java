package com.javaclaw.desktop.settings;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.Session;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteRegistrationPresenterTest {
    private static final WorkspaceId WORKSPACE = DesktopTestFixtures.workspace().id();

    @Test
    void 启动失败重试保留地址和幂等身份且关闭后的迟到浏览器被取消() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            gateway.begin = CompletableFuture.failedFuture(new IllegalStateException("连接中断"));
            var presenter = presenter(gateway);
            presenter.begin("http://example.com");
            assertTrue(gateway.calls.isEmpty());
            presenter.begin("https://z.example.com");
            assertEquals(
                    SiteRegistrationPresenter.Phase.START_FAILED,
                    presenter.state().phase());
            gateway.begin = new CompletableFuture<>();
            presenter.begin("https://ignored.example.com");
            assertEquals(gateway.calls.getFirst(), gateway.calls.getLast());
            presenter.close();
            gateway.begin.complete(SiteRegistrationTestGateway.session(State.ACTIVE));
            assertEquals(1, gateway.count("cancel"));
            assertEquals(WORKSPACE, gateway.calls.getLast().workspace());
            presenter.close();
            assertEquals(1, gateway.count("cancel"));
        });
    }

    @Test
    void 完成绑定当前页面与候选且进行中拒绝重复写入() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            List<SiteRegistrationContracts.Completed> delivered = new ArrayList<>();
            var presenter = new SiteRegistrationPresenter(gateway, WORKSPACE, delivered::add);
            presenter.begin("https://z.example.com");
            presenter.allowOrigin("https://login.example.com/path");
            presenter.complete("测试", Optional.of("unknown-candidate"));
            assertEquals(0, gateway.count("origin"));
            assertEquals(0, gateway.count("complete"));
            presenter.complete("测试", Optional.of("candidate"));
            assertTrue(presenter.pending());
            var request = (SiteRegistrationContracts.CompleteRequest)
                    gateway.calls.getLast().request();
            assertEquals(2, request.expectedGeneration());
            assertEquals(3, request.expectedPageRevision());
            assertEquals(Optional.of("candidate"), request.credentialId());
            presenter.complete("重复", Optional.empty());
            presenter.allowOrigin("https://login.example.com");
            presenter.refresh();
            assertEquals(1, gateway.count("complete"));
            gateway.complete.complete(SiteRegistrationTestGateway.session(State.COMPLETED));
            assertEquals(1, delivered.size());
            assertFalse(presenter.pending());
            presenter.close();
            assertEquals(0, gateway.count("cancel"));
        });
    }

    @Test
    void 保存通信失败只查询原会话并从已提交结果恢复() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            List<SiteRegistrationContracts.Completed> delivered = new ArrayList<>();
            var presenter = new SiteRegistrationPresenter(gateway, WORKSPACE, delivered::add);
            presenter.begin("https://z.example.com");
            presenter.complete("测试", Optional.empty());
            gateway.complete.completeExceptionally(new IllegalStateException("回执丢失"));
            assertEquals(
                    SiteRegistrationPresenter.Phase.UNKNOWN, presenter.state().phase());
            presenter.refresh();
            presenter.complete("不能重放", Optional.empty());
            presenter.cancel();
            assertEquals(
                    SiteRegistrationPresenter.Phase.UNKNOWN, presenter.state().phase());
            assertEquals(1, gateway.count("complete"));
            assertEquals(0, gateway.count("cancel"));
            gateway.status = CompletableFuture.completedFuture(SiteRegistrationTestGateway.session(State.COMPLETED));
            presenter.refresh();
            assertEquals(1, delivered.size());
            presenter.refresh();
            assertEquals(2, gateway.count("status"));
            presenter.close();
        });
    }

    @Test
    void 授权回执丢失仅在代次和来源事实均确认后恢复编辑() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            gateway.origin = CompletableFuture.failedFuture(new IllegalStateException("授权回执丢失"));
            var presenter = presenter(gateway);
            presenter.begin("https://z.example.com");
            presenter.allowOrigin("https://login.example.com");
            assertEquals(
                    SiteRegistrationPresenter.Phase.UNKNOWN, presenter.state().phase());
            gateway.status = CompletableFuture.completedFuture(withOrigin(2, true));
            presenter.refresh();
            assertEquals(
                    SiteRegistrationPresenter.Phase.UNKNOWN, presenter.state().phase());
            gateway.status = CompletableFuture.completedFuture(withOrigin(3, false));
            presenter.refresh();
            assertEquals(
                    SiteRegistrationPresenter.Phase.UNKNOWN, presenter.state().phase());
            gateway.status = CompletableFuture.completedFuture(withOrigin(3, true));
            presenter.refresh();
            assertEquals(
                    SiteRegistrationPresenter.Phase.ACTIVE, presenter.state().phase());
            assertEquals("来源已允许，请在浏览器中刷新页面或继续登录。", presenter.state().message());
            assertEquals(1, gateway.count("origin"));
            presenter.close();
            assertEquals(1, gateway.count("cancel"));
        });
    }

    @Test
    void 关闭授权未知会话可以清理而取消未知等待原终态() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            gateway.origin = CompletableFuture.failedFuture(new IllegalStateException("连接中断"));
            var authorizing = presenter(gateway);
            authorizing.begin("https://z.example.com");
            authorizing.allowOrigin("https://login.example.com");
            authorizing.close();
            assertEquals(1, gateway.count("cancel"));
            gateway.cancel = CompletableFuture.failedFuture(new IllegalStateException("取消回执丢失"));
            var cancelling = presenter(gateway);
            cancelling.begin("https://z.example.com");
            cancelling.cancel();
            assertEquals(
                    SiteRegistrationPresenter.Phase.UNKNOWN, cancelling.state().phase());
            gateway.status = CompletableFuture.completedFuture(SiteRegistrationTestGateway.session(State.CANCELLED));
            cancelling.refresh();
            assertEquals(
                    SiteRegistrationPresenter.Phase.TERMINAL, cancelling.state().phase());
            cancelling.close();
            assertEquals(2, gateway.count("cancel"));
        });
    }

    @Test
    void 版本或权限拒绝刷新当前状态并保留可修改上下文() {
        FxTestSupport.run(() -> {
            for (int code : List.of(
                    ProtocolErrorCode.REVISION_CONFLICT,
                    ProtocolErrorCode.PERMISSION_DENIED,
                    ProtocolErrorCode.INVALID_PARAMS)) {
                var gateway = new SiteRegistrationTestGateway();
                gateway.complete = CompletableFuture.failedFuture(
                        new RemoteRpcException(new JsonRpcError(code, "拒绝", Optional.empty())));
                var presenter = presenter(gateway);
                presenter.begin("https://z.example.com");
                presenter.complete("保留名称", Optional.empty());
                assertEquals(
                        SiteRegistrationPresenter.Phase.ACTIVE,
                        presenter.state().phase());
                assertEquals(1, gateway.count("status"));
                presenter.close();
                assertEquals(1, gateway.count("cancel"));
            }
        });
    }

    @Test
    void 额外授权使用精确来源和当前代次且关闭不竞争未知提交() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            var presenter = presenter(gateway);
            presenter.begin("https://z.example.com");
            presenter.allowOrigin("https://login.example.com");
            var request = (SiteRegistrationContracts.OriginRequest)
                    gateway.calls.getLast().request();
            assertEquals(2, request.expectedGeneration());
            assertEquals("https://login.example.com", request.origin().toString());
            assertEquals("来源已允许，请在浏览器中刷新页面或继续登录。", presenter.state().message());
            presenter.refresh();
            assertEquals("来源已允许，请在浏览器中刷新页面或继续登录。", presenter.state().message());
            presenter.complete("测试", Optional.empty());
            presenter.close();
            gateway.complete.complete(SiteRegistrationTestGateway.session(State.COMPLETED));
            assertEquals(0, gateway.count("cancel"));
        });
    }

    @Test
    void 初始跨来源跳转被阻断时提示授权且页面前进后恢复登录提示() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            Session active = SiteRegistrationTestGateway.session(State.ACTIVE);
            Session waiting = new Session(
                    active.sessionId(),
                    active.state(),
                    active.access(),
                    new SiteRegistrationContracts.Page(0, Optional.empty(), "", List.of()),
                    Optional.empty());
            gateway.begin = CompletableFuture.completedFuture(waiting);
            gateway.origin = CompletableFuture.completedFuture(waiting);
            var presenter = presenter(gateway);
            presenter.begin("https://z.example.com");
            assertEquals("页面正在等待来源授权，请在下方“额外访问来源”核对并允许所需来源。", presenter.state().message());
            presenter.allowOrigin("https://login.example.com");
            assertEquals("来源已允许，请在浏览器中刷新页面或继续登录。", presenter.state().message());
            presenter.refresh();
            assertEquals("请在浏览器中完成登录，再点击完成添加。", presenter.state().message());
            assertEquals(1, gateway.count("begin"));
            assertEquals(1, gateway.count("origin"));
            presenter.close();
        });
    }

    @Test
    void 关闭后迟到状态不能通知新页面且未知状态不重新写入() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            gateway.status = new UncancellableStatus();
            var presenter = presenter(gateway);
            List<SiteRegistrationPresenter.Snapshot> snapshots = new ArrayList<>();
            presenter.subscribe(snapshots::add);
            presenter.begin("https://z.example.com");
            presenter.refresh();
            int count = snapshots.size();
            presenter.close();
            gateway.status.complete(SiteRegistrationTestGateway.session(State.COMPLETED));
            presenter.refresh();
            presenter.begin("https://z.example.com");
            assertEquals(count, snapshots.size());
            assertEquals(1, gateway.count("status"));
            assertEquals(1, gateway.count("begin"));
        });
    }

    @Test
    void 状态读取失败保留当前会话且终态停止查询() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            var presenter = presenter(gateway);
            presenter.begin("https://z.example.com");
            gateway.status = CompletableFuture.failedFuture(new IllegalStateException("离线"));
            presenter.refresh();
            assertEquals(
                    SiteRegistrationPresenter.Phase.ACTIVE, presenter.state().phase());
            assertFalse(presenter.state().reading());
            gateway.status = CompletableFuture.completedFuture(SiteRegistrationTestGateway.session(State.EXPIRED));
            presenter.refresh();
            assertEquals(
                    SiteRegistrationPresenter.Phase.TERMINAL, presenter.state().phase());
            presenter.refresh();
            presenter.cancel();
            presenter.close();
            assertEquals(2, gateway.count("status"));
            assertEquals(0, gateway.count("cancel"));
        });
    }

    private static Session withOrigin(long generation, boolean included) {
        Session current = SiteRegistrationTestGateway.session(State.ACTIVE);
        Set<URI> origins = included
                ? Set.of(SiteRegistrationTestGateway.ORIGIN, URI.create("https://login.example.com"))
                : Set.of(SiteRegistrationTestGateway.ORIGIN);
        var access = new SiteRegistrationContracts.Access(
                generation, origins, Set.of(), current.access().expiresAt());
        return new Session(current.sessionId(), current.state(), access, current.page(), current.completed());
    }

    private static SiteRegistrationPresenter presenter(SiteRegistrationTestGateway gateway) {
        return new SiteRegistrationPresenter(gateway, WORKSPACE, ignored -> {});
    }

    /** 模拟后台传输不能被 UI 的取消请求中断，检验 epoch 而非 Future 状态。 */
    private static final class UncancellableStatus extends CompletableFuture<Session> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }
}
