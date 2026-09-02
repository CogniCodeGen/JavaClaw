package com.javaclaw.desktop;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.settings.SdkAutomationJobSettingsGateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AutomationJobSdkGatewayTest {
    @Test
    void 设置网关只通过Sdk读取详情并提交带revision的幂等动作() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        DesktopPresenter desktop = new DesktopPresenter(
                server::client, Runnable::run, Clock.fixed(DesktopTestFixtures.NOW, ZoneOffset.UTC));
        try {
            desktop.reconnect().join();
            SdkAutomationJobSettingsGateway gateway = new SdkAutomationJobSettingsGateway(desktop);
            var page = gateway.jobs(
                            Optional.of(server.workspace().id()),
                            Optional.of("com.javaclaw.workflow"),
                            Set.of(ExecutionState.RUNNING),
                            Optional.empty(),
                            40)
                    .toCompletableFuture()
                    .join();
            var current = page.jobs().getFirst();
            var detail = gateway.job(current.id()).toCompletableFuture().join();

            var paused = gateway.pause(current, CommandOptions.create(current.revision()))
                    .toCompletableFuture()
                    .join();

            assertEquals(1, server.jobLists.get());
            assertEquals(1, server.jobReads.get());
            assertEquals(1, detail.units().size());
            assertEquals(ExecutionState.PAUSED, paused.state());
            assertEquals(current.revision(), server.lastJobExpectedRevision);
            assertFalse(server.lastJobIdempotencyKey.isBlank());
        } finally {
            desktop.close();
        }
    }
}
