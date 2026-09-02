package com.javaclaw.launcher.tray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayApplicationControllerTest {
    @Test
    void 托盘命令目录不包含Schedule动作() {
        assertEquals(
                List.of("OPEN_MAIN", "START_SERVER", "STOP_SERVER", "RESTART_SERVER", "REFRESH"),
                Arrays.stream(TrayCommand.values()).map(Enum::name).toList());
    }

    @Test
    void 启动服务打开窗口且窗口退出绝不停止Server() throws Exception {
        Fixture fixture = fixture(false);
        try (TrayApplicationController controller = fixture.controller()) {
            controller.start();
            fixture.view().emit(TrayCommand.START_SERVER);
            fixture.view().emit(TrayCommand.OPEN_MAIN);

            assertEquals(1, fixture.server().starts);
            assertEquals(1, fixture.mainWindow().opens);
            assertTrue(fixture.view().latest().mainWindowOpen());

            fixture.mainWindow().exit();

            assertFalse(fixture.view().latest().mainWindowOpen());
            assertEquals(0, fixture.server().stops);
        }
        assertEquals(0, fixture.server().stops);
        assertTrue(fixture.view().closed);
    }

    @Test
    void 活动Lease拒绝停止时保留运行状态和通俗原因() throws Exception {
        Fixture fixture = fixture(true);
        fixture.server().stopResult = TrayServerControl.ControlResult.rejected("存在活动 Schedule lease");
        try (TrayApplicationController controller = fixture.controller()) {
            controller.start();

            fixture.view().emit(TrayCommand.STOP_SERVER);

            assertEquals(1, fixture.server().stops);
            assertEquals(TrayState.ServerState.RUNNING, fixture.view().latest().server());
            assertEquals("存在活动 Schedule lease", fixture.view().latest().error().orElseThrow());
        }
    }

    @Test
    void 安全停止与重启由Supervisor结果驱动() throws Exception {
        Fixture fixture = fixture(true);
        try (TrayApplicationController controller = fixture.controller()) {
            controller.start();

            fixture.view().emit(TrayCommand.STOP_SERVER);
            assertEquals(TrayState.ServerState.STOPPED, fixture.view().latest().server());

            fixture.view().emit(TrayCommand.START_SERVER);
            fixture.view().emit(TrayCommand.RESTART_SERVER);

            assertEquals(1, fixture.server().stops);
            assertEquals(1, fixture.server().restarts);
            assertEquals(TrayState.ServerState.RUNNING, fixture.view().latest().server());
        }
    }

    @Test
    void 后台异常转为状态而不逃逸到Awt线程() throws Exception {
        Fixture fixture = fixture(false);
        fixture.server().failure = new IllegalStateException("本地 transport 不可用");
        try (TrayApplicationController controller = fixture.controller()) {
            controller.start();

            assertEquals("操作失败", fixture.view().latest().message());
            assertEquals("本地 transport 不可用", fixture.view().latest().error().orElseThrow());
        }
    }

    @Test
    void 空白异常信息回退到异常类型且空控制结果也安全失败() throws Exception {
        Fixture fixture = fixture(false);
        fixture.server().failure = new IllegalStateException("   ");
        try (TrayApplicationController controller = fixture.controller()) {
            controller.start();
            assertEquals(
                    "IllegalStateException", fixture.view().latest().error().orElseThrow());

            fixture.server().failure = null;
            fixture.server().stopResult = null;
            fixture.view().emit(TrayCommand.STOP_SERVER);
            assertEquals("result", fixture.view().latest().error().orElseThrow());
        }
    }

    @Test
    void 已打开主窗口不会重复启动且关闭控制器后回调不再渲染() throws Exception {
        Fixture fixture = fixture(true);
        fixture.mainWindow().running = true;
        fixture.controller().start();

        fixture.view().emit(TrayCommand.OPEN_MAIN);
        assertEquals(0, fixture.mainWindow().opens);
        int renderedBeforeClose = fixture.view().states.size();

        fixture.controller().close();
        fixture.mainWindow().exit();
        fixture.controller().submit(TrayCommand.REFRESH);
        fixture.controller().close();
        assertEquals(renderedBeforeClose, fixture.view().states.size());
        assertThrows(IllegalStateException.class, fixture.controller()::start);
    }

    @Test
    void 停止被拒绝时准确投影未运行状态() throws Exception {
        Fixture fixture = fixture(false);
        fixture.server().stopResult = TrayServerControl.ControlResult.rejected("存在活动任务");
        try (TrayApplicationController controller = fixture.controller()) {
            controller.start();

            fixture.view().emit(TrayCommand.STOP_SERVER);

            assertEquals(TrayState.ServerState.STOPPED, fixture.view().latest().server());
            assertEquals("操作被安全策略拒绝", fixture.view().latest().message());
        }
    }

    @Test
    void 默认执行器和双重关闭都能释放而不停止服务() throws Exception {
        Fixture fixture = fixture(false);
        TrayApplicationController controller =
                new TrayApplicationController(fixture.server(), fixture.mainWindow(), fixture.view());

        controller.close();
        controller.close();

        assertTrue(fixture.view().closed);
        assertEquals(0, fixture.server().stops);
    }

    @Test
    void 关闭时保留首个失败并附加执行器失败() {
        FakeView view = new FakeView();
        RuntimeException viewFailure = new IllegalStateException("view close failed");
        view.closeFailure = viewFailure;
        TrayApplicationController controller =
                new TrayApplicationController(new FakeServer(false), new FakeMainWindow(), view, Runnable::run, () -> {
                    throw new Exception("executor close failed");
                });

        Exception actual = assertThrows(Exception.class, controller::close);

        assertEquals(viewFailure, actual);
        assertEquals("executor close failed", actual.getSuppressed()[0].getMessage());
    }

    @Test
    void 执行器单独关闭失败仍返回原始原因() {
        TrayApplicationController controller = new TrayApplicationController(
                new FakeServer(false), new FakeMainWindow(), new FakeView(), Runnable::run, () -> {
                    throw new Exception("executor only");
                });

        Exception actual = assertThrows(Exception.class, controller::close);

        assertEquals("executor only", actual.getMessage());
    }

    private static Fixture fixture(boolean running) {
        FakeServer server = new FakeServer(running);
        FakeMainWindow mainWindow = new FakeMainWindow();
        FakeView view = new FakeView();
        TrayApplicationController controller =
                new TrayApplicationController(server, mainWindow, view, Runnable::run, () -> {});
        return new Fixture(server, mainWindow, view, controller);
    }

    private record Fixture(
            FakeServer server, FakeMainWindow mainWindow, FakeView view, TrayApplicationController controller) {}

    private static final class FakeServer implements TrayServerControl {
        private boolean running;
        private int starts;
        private int stops;
        private int restarts;
        private RuntimeException failure;
        private ControlResult stopResult = ControlResult.success();

        private FakeServer(boolean running) {
            this.running = running;
        }

        @Override
        public boolean running() {
            if (failure != null) {
                throw failure;
            }
            return running;
        }

        @Override
        public void start() {
            starts++;
            running = true;
        }

        @Override
        public ControlResult stop() {
            stops++;
            if (stopResult != null && stopResult.accepted()) {
                running = false;
            }
            return stopResult;
        }

        @Override
        public ControlResult restart() {
            restarts++;
            running = true;
            return ControlResult.success();
        }
    }

    private static final class FakeMainWindow implements MainWindowControl {
        private boolean running;
        private int opens;
        private Runnable onExit = () -> {};

        @Override
        public boolean running() {
            return running;
        }

        @Override
        public void open(Runnable callback) {
            if (running) {
                return;
            }
            running = true;
            opens++;
            onExit = callback;
        }

        private void exit() {
            running = false;
            onExit.run();
        }
    }

    private static final class FakeView implements TrayView {
        private final List<TrayState> states = new ArrayList<>();
        private Consumer<TrayCommand> commands = ignored -> {};
        private boolean closed;
        private RuntimeException closeFailure;

        @Override
        public void bind(Consumer<TrayCommand> commandConsumer) {
            commands = commandConsumer;
        }

        @Override
        public void render(TrayState state) {
            states.add(state);
        }

        @Override
        public void close() {
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private void emit(TrayCommand command) {
            commands.accept(command);
        }

        private TrayState latest() {
            return states.getLast();
        }
    }
}
