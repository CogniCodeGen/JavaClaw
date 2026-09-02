package com.javaclaw.launcher.tray;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.protocol.DiagnosticsRpcContracts;
import com.javaclaw.protocol.LocalTransport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTrayServerControlTest {
    @Test
    void 已停止Server无需Rpc即可幂等停止和启动() throws Exception {
        FakeProcess process = new FakeProcess(false);
        int[] requests = {0};
        ProtocolTrayServerControl control = control(process, () -> {
            requests[0]++;
            return accepted();
        });

        assertTrue(control.stop().accepted());
        control.start();

        assertEquals(0, requests[0]);
        assertEquals(1, process.starts);
        assertTrue(control.running());
    }

    @Test
    void 服务端Lease拒绝会原样返回且不重启() throws Exception {
        FakeProcess process = new FakeProcess(true);
        ProtocolTrayServerControl control = control(
                process,
                () -> new DiagnosticsRpcContracts.ServerStopResult(false, 1, 1, Optional.of("存在活动 Schedule lease")));

        TrayServerControl.ControlResult stopped = control.stop();
        TrayServerControl.ControlResult restarted = control.restart();

        assertFalse(stopped.accepted());
        assertFalse(restarted.accepted());
        assertEquals("存在活动 Schedule lease", restarted.reason().orElseThrow());
        assertEquals(0, process.starts);
    }

    @Test
    void 接受停止后等待Transport离线再允许重启() throws Exception {
        FakeProcess process = new FakeProcess(true);
        ProtocolTrayServerControl control = control(process, () -> {
            process.running = false;
            return accepted();
        });

        assertTrue(control.restart().accepted());

        assertEquals(1, process.starts);
        assertTrue(process.running);
    }

    @Test
    void 接受响应但Transport未退出时拒绝伪报成功() {
        FakeProcess process = new FakeProcess(true);
        ProtocolTrayServerControl control =
                new ProtocolTrayServerControl(process, ProtocolTrayServerControlTest::accepted, Duration.ofNanos(1));

        assertThrows(IOException.class, control::stop);
        assertTrue(process.running);
    }

    private static ProtocolTrayServerControl control(FakeProcess process, ProtocolTrayServerControl.StopCommand stop) {
        return new ProtocolTrayServerControl(process, stop, Duration.ofSeconds(1));
    }

    private static DiagnosticsRpcContracts.ServerStopResult accepted() {
        return new DiagnosticsRpcContracts.ServerStopResult(true, 1, 0, Optional.empty());
    }

    private static final class FakeProcess implements TrayServerProcess {
        private boolean running;
        private int starts;

        private FakeProcess(boolean running) {
            this.running = running;
        }

        @Override
        public boolean running() {
            return running;
        }

        @Override
        public void start() {
            starts++;
            running = true;
        }

        @Override
        public LocalTransport transport() {
            throw new AssertionError("injected stop command must not open transport");
        }
    }
}
