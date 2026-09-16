package com.javaclaw.builtin.extensions;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SiteRegistrationContributionsTest {
    private static final String SESSION = "91b7a5fa-40eb-4447-9466-e5e13aa5b68c";
    private static final URI ORIGIN = URI.create("https://example.com");
    private final BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();

    @Test
    void 设置命令把原参数与稳定幂等身份交给宿主且不单独提交文档() throws Exception {
        try (SiteExtension extension = new SiteExtension()) {
            var started = support.start(extension);
            for (String operation : List.of(
                    "registration.begin", "registration.origin", "registration.complete", "registration.cancel")) {
                ExtensionRequest request = request(operation, Optional.of("one-key"), 0);
                support.service = (caller, service, payload) -> {
                    assertEquals(SiteRegistrationContracts.SERVICE, service);
                    var task = support.payloads.decode(payload, SiteRegistrationContracts.ServiceRequest.class);
                    assertEquals(operation, task.operation());
                    assertEquals(Optional.of("one-key"), task.idempotencyKey());
                    assertEquals(request.payload(), task.payload());
                    return support.payloads.encode(session());
                };
                assertEquals(
                        session(), support.decode(started.command(request), SiteRegistrationContracts.Session.class));
                // 回执必须随宿主网站及密文事务提交，扩展不能留下独立的成功记录。
                assertFalse(support.store
                        .recoverCommand(
                                extension.descriptor().id(),
                                operation,
                                "one-key",
                                request.payload().sha256())
                        .isPresent());
            }
        }
    }

    @Test
    void 状态查询没有写身份且登记命令不会发布为模型工具() throws Exception {
        try (SiteExtension extension = new SiteExtension()) {
            var started = support.start(extension);
            support.service = (caller, service, payload) -> {
                var task = support.payloads.decode(payload, SiteRegistrationContracts.ServiceRequest.class);
                assertEquals("registration.status", task.operation());
                assertEquals(Optional.empty(), task.idempotencyKey());
                return support.payloads.encode(session());
            };
            assertEquals(
                    session(),
                    support.decode(
                            started.query(request("registration.status", Optional.empty(), 0)),
                            SiteRegistrationContracts.Session.class));
            assertFalse(started.contributions().stream()
                    .filter(ExtensionContributions.Tool.class::isInstance)
                    .anyMatch(value -> value.contributionId().contains("registration")));
        }
    }

    @Test
    void 聊天和其他Workspace不能借用设置页登记入口() throws Exception {
        try (SiteExtension extension = new SiteExtension()) {
            var started = support.start(extension);
            AtomicInteger calls = new AtomicInteger();
            support.service = (caller, service, payload) -> {
                calls.incrementAndGet();
                return support.payloads.encode(session());
            };
            var original = request("registration.begin", Optional.of("key"), 0);
            for (Optional<TurnId> turn : List.of(Optional.<TurnId>empty(), Optional.of(TurnId.random()))) {
                var chat = new ExtensionRequest(
                        original.workspaceId(),
                        Optional.of(ThreadId.random()),
                        turn,
                        original.operation(),
                        original.payload(),
                        original.idempotencyKey(),
                        0,
                        Optional.empty());
                assertThrows(SecurityException.class, () -> started.command(chat));
            }
            var wrongWorkspace = new ExtensionRequest(
                    WorkspaceId.random(),
                    Optional.empty(),
                    Optional.empty(),
                    original.operation(),
                    original.payload(),
                    original.idempotencyKey(),
                    0,
                    Optional.empty());
            assertThrows(SecurityException.class, () -> started.command(wrongWorkspace));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void 拒绝缺失命令身份以及混入文档版本或写身份的查询() throws Exception {
        try (SiteExtension extension = new SiteExtension()) {
            var started = support.start(extension);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.command(request("registration.begin", Optional.empty(), 0)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.command(request("registration.begin", Optional.of("key"), 1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.query(request("registration.status", Optional.of("key"), 0)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.query(request("registration.status", Optional.empty(), 1)));
        }
    }

    private ExtensionRequest request(String operation, Optional<String> key, long revision) {
        return new ExtensionRequest(
                support.workspaceId,
                Optional.empty(),
                Optional.empty(),
                operation,
                support.payloads.encode(new SiteRegistrationContracts.SessionRequest(SESSION)),
                key,
                revision,
                Optional.empty());
    }

    private static SiteRegistrationContracts.Session session() {
        return new SiteRegistrationContracts.Session(
                SESSION,
                SiteRegistrationContracts.State.ACTIVE,
                new SiteRegistrationContracts.Access(
                        1, Set.of(ORIGIN), Set.of(), BuiltinExtensionTestSupport.NOW.plusSeconds(600)),
                new SiteRegistrationContracts.Page(1, Optional.of(ORIGIN), "示例网站", List.of()),
                Optional.empty());
    }
}
