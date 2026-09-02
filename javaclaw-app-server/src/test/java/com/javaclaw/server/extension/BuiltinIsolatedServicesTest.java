package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinIsolatedServicesTest {
    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.parse("714e2407-e362-4ce4-89b7-f8cd76b8f04d");

    private final CanonicalJson json = new CanonicalJson();

    @TempDir
    Path temporaryDirectory;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 生产组合根在未配置发行镜像时保留管理路由() {
        String browser = System.getProperty(BrowserWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY);
        String knowledge = System.getProperty(KnowledgeWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY);
        String skill = System.getProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY);
        System.clearProperty(BrowserWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY);
        System.clearProperty(KnowledgeWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY);
        System.clearProperty(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY);
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        Clock clock = Clock.systemUTC();
        try (SecretVaultService vault =
                        new SecretVaultService(database, new MemoryProtector(), json, clock, new SecureRandom());
                BuiltinIsolatedServices services = BuiltinIsolatedServices.production(
                        database,
                        new AttachmentService(database, json, clock),
                        vault,
                        new PrivateNetworkGrantService(database, json, clock),
                        json,
                        clock)) {
            BuiltinIsolatedServices.Availability availability = services.availability();
            assertFalse(availability.browser());
            assertFalse(availability.knowledge());
            assertFalse(availability.skillExecution());
            assertTrue(services.oauthBrowser().isEmpty());
        } finally {
            restore(BrowserWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY, browser);
            restore(KnowledgeWorkerRuntimeFactory.IMAGE_ROOT_PROPERTY, knowledge);
            restore(SkillResourceRuntimeLayout.IMAGE_ROOT_PROPERTY, skill);
        }
    }

    @Test
    void 不可用路由暴露脱敏状态且关闭幂等() {
        BuiltinIsolatedServices services = BuiltinIsolatedServices.browserUnavailable();

        BuiltinIsolatedServices.Availability availability = services.availability();
        assertFalse(availability.browser());
        assertFalse(availability.knowledge());
        assertFalse(availability.skillExecution());
        assertTrue(services.oauthBrowser().isEmpty());

        services.close();
        services.close();
    }

    @Test
    void Site失效与会话列表在Worker不可用时仍有明确结果() throws Exception {
        try (BuiltinIsolatedServices services = BuiltinIsolatedServices.browserUnavailable()) {
            CanonicalPayload invalidation = services.invoke(invocation(
                    BuiltinExtensionIds.SITE,
                    SiteContracts.BROWSER_INVALIDATE_SERVICE,
                    new SiteContracts.AuthorityInvalidation("site", 2)));
            CanonicalPayload list = services.invoke(
                    invocation(BuiltinExtensionIds.SITE, SiteContracts.BROWSER_LOGIN_LIST_SERVICE, Map.of()));

            assertTrue(json.decode(invalidation, Map.class).isEmpty());
            SiteContracts.LoginSessionList sessions = json.decode(list, SiteContracts.LoginSessionList.class);
            assertTrue(sessions.sessions().isEmpty());
            assertFalse(sessions.interactiveLoginAvailable());
        }
    }

    @Test
    void Site其余页面与登录服务在Worker不可用时失败关闭() {
        try (BuiltinIsolatedServices services = BuiltinIsolatedServices.browserUnavailable()) {
            List<String> serviceIds = List.of(
                    SiteContracts.BROWSER_SNAPSHOT_SERVICE,
                    SiteContracts.BROWSER_LOGIN_BEGIN_SERVICE,
                    SiteContracts.BROWSER_LOGIN_STATUS_SERVICE,
                    SiteContracts.BROWSER_LOGIN_SAVE_SERVICE,
                    SiteContracts.BROWSER_LOGIN_CANCEL_SERVICE);

            for (String serviceId : serviceIds) {
                assertThrows(
                        IllegalStateException.class,
                        () -> services.invoke(invocation(BuiltinExtensionIds.SITE, serviceId, Map.of())));
            }
        }
    }

    @Test
    void Knowledge与Skill只能访问各自白名单服务() throws Exception {
        try (BuiltinIsolatedServices services = BuiltinIsolatedServices.browserUnavailable()) {
            assertThrows(
                    IllegalStateException.class,
                    () -> services.invoke(invocation(
                            BuiltinExtensionIds.KNOWLEDGE, KnowledgeContracts.EXTRACTION_SERVICE, Map.of())));
            SkillContracts.ResourceExecutionInvocation status = new SkillContracts.ResourceExecutionInvocation(
                    SkillContracts.ResourceExecutionOperation.STATUS, Optional.empty(), List.of());
            CanonicalPayload response = services.invoke(
                    invocation(BuiltinExtensionIds.SKILL, SkillContracts.RESOURCE_EXECUTION_SERVICE, status));
            assertFalse(json.decode(response, SkillContracts.ResourceExecutionAvailability.class)
                    .executable());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> services.invoke(
                            invocation(BuiltinExtensionIds.MEMORY, KnowledgeContracts.EXTRACTION_SERVICE, Map.of())));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> services.invoke(invocation(BuiltinExtensionIds.SITE, "site.browser.unknown", Map.of())));
            assertThrows(NullPointerException.class, () -> services.invoke(null));
        }
    }

    private IsolatedServiceInvocation invocation(String caller, String serviceId, Object request) {
        return new IsolatedServiceInvocation(
                new ExtensionId(caller),
                WORKSPACE_ID,
                permission(),
                serviceId,
                json.encode(request),
                new CancellationSource());
    }

    private static PermissionProfile permission() {
        return new PermissionProfile(
                "isolated-services",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), false),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(512L * 1024 * 1024, 64L * 1024, 4, 128));
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String keyId) {
            return Optional.ofNullable(keys.get(keyId)).map(byte[]::clone);
        }

        @Override
        public void store(String keyId, byte[] key) {
            keys.put(keyId, key.clone());
        }

        @Override
        public void delete(String keyId) {
            byte[] removed = keys.remove(keyId);
            if (removed != null) {
                Arrays.fill(removed, (byte) 0);
            }
        }
    }
}
