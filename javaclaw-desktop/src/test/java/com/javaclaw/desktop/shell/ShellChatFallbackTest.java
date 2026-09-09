package com.javaclaw.desktop.shell;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.client.facade.TurnStreamSnapshot;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.ThreadState;
import com.javaclaw.desktop.state.TranscriptState;
import com.javaclaw.desktop.web.WebSurfaceHost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellChatFallbackTest {
    @Test
    void 原生普通历史没有文档按钮而附件文件和截断正文使用超链接() {
        FxTestSupport.run(() -> {
            try (ReadingFixture fixture = new ReadingFixture()) {
                DesktopState seed = state("", TurnStreamKind.COMMITTED);
                WorkspaceId workspace =
                        seed.threads().selectedWorkspace().orElseThrow().id();
                fixture.surfaces.render(new DesktopState(
                        seed.connection(),
                        seed.navigation(),
                        seed.threads(),
                        new TranscriptState(List.of(), 3, true, Optional.empty(), false, documentHistory(workspace)),
                        seed.interaction()));
                fixture.layout();
                List<String> links = fixture.host.lookupAll(".hyperlink").stream()
                        .map(Hyperlink.class::cast)
                        .filter(Hyperlink::isVisible)
                        .map(Hyperlink::getText)
                        .sorted()
                        .toList();
                assertEquals(List.of("查看完整消息", "查看引用文件", "设计文档.md"), links);
                assertFalse(fixture.host.lookupAll(".button").stream()
                        .map(Button.class::cast)
                        .anyMatch(
                                button -> button.isVisible() && button.getText().equals("打开文档")));
            }
        });
    }

    @Test
    void 原生向上滚动暂停跟随且新分片保留位置直到用户返回最新() {
        FxTestSupport.run(() -> {
            try (ReadingFixture fixture = new ReadingFixture()) {
                fixture.render("最新分片一");
                ListView<?> list = (ListView<?>) fixture.host.lookup("#transcriptSummary");
                list.scrollTo(20);
                fixture.layout();
                assertTrue(fixture.following());
                Event.fireEvent(list, upwardScroll());
                fixture.layout();
                assertFalse(fixture.following());
                double readingPosition = fixture.bar().getValue();
                assertTrue(readingPosition < fixture.bar().getMax());
                fixture.render("最新分片二");
                assertEquals(readingPosition, fixture.bar().getValue(), 0.001);
                Button latest = fixture.latest();
                assertTrue(latest.isVisible());
                latest.fire();
                fixture.render("最新分片二");
                assertTrue(fixture.following());
                assertFalse(latest.isVisible());
                assertEquals(fixture.bar().getMax(), fixture.bar().getValue(), 0.001);
            }
        });
    }

    @Test
    void 原生键盘和滚动条阅读暂停跟随但程序定位不会() {
        FxTestSupport.run(() -> {
            try (ReadingFixture fixture = new ReadingFixture()) {
                fixture.render("最新正文");
                ListView<?> list = (ListView<?>) fixture.host.lookup("#transcriptSummary");
                for (KeyCode code : List.of(KeyCode.UP, KeyCode.PAGE_UP, KeyCode.HOME)) {
                    Event.fireEvent(list, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
                    assertFalse(fixture.following());
                    fixture.latest().fire();
                }
                list.scrollTo(10);
                fixture.layout();
                assertTrue(fixture.following());
                Event.fireEvent(
                        fixture.bar(),
                        new MouseEvent(
                                MouseEvent.MOUSE_PRESSED,
                                1,
                                1,
                                1,
                                1,
                                MouseButton.PRIMARY,
                                1,
                                false,
                                false,
                                false,
                                false,
                                true,
                                false,
                                false,
                                true,
                                false,
                                true,
                                null));
                assertFalse(fixture.following());
            }
        });
    }

    @Test
    void 实际主壳流式切换简版保留正文未完成状态和历史入口() {
        DesktopPresenter presenter = new DesktopPresenter(
                notifications -> {
                    throw new IllegalStateException("验收不建立网络连接");
                },
                Platform::runLater,
                Clock.systemUTC());
        FxTestSupport.run(() -> {
            StackPane host = new StackPane();
            VBox progress = new VBox();
            ShellSidePanels panels = new ShellSidePanels(new BorderPane(), new VBox(), progress, new Button());
            ShellWebSurfaces surfaces = new ShellWebSurfaces(presenter, progress, host, new ListView<>(), panels);
            Stage stage = new Stage();
            Scene scene = new Scene(host, 760, 500);
            DesktopStylesheets.apply(scene);
            stage.setScene(scene);
            stage.show();
            try {
                surfaces.render(state("仍在输出的正文", TurnStreamKind.TEXT_DELTA));
                ((WebSurfaceHost) host.getChildren().getFirst()).useFallback();
                host.applyCss();
                host.layout();
                assertTrue(host.lookupAll(".label").stream()
                        .map(Label.class::cast)
                        .anyMatch(label -> label.isVisible() && label.getText().contains("仍在输出的正文")));
                assertTrue(host.lookupAll(".button").stream()
                        .map(Button.class::cast)
                        .anyMatch(
                                button -> button.isVisible() && button.getText().equals("加载更早消息")));
                surfaces.render(state("被中断的正文", TurnStreamKind.CLOSED));
                host.applyCss();
                host.layout();
                assertTrue(host.lookupAll(".label").stream()
                        .map(Label.class::cast)
                        .anyMatch(label -> label.getText().contains("未完成")));
            } finally {
                surfaces.close();
                panels.close();
                stage.close();
                try {
                    presenter.close();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        });
    }

    private static DesktopState state(String text, TurnStreamKind kind) {
        Workspace workspace = new Workspace(
                WorkspaceId.random(),
                "验收工作空间",
                Path.of("/private/tmp"),
                WorkspaceLifecycle.ACTIVE,
                1,
                Instant.EPOCH,
                Instant.EPOCH);
        TurnStreamSnapshot stream = new TurnStreamSnapshot(
                TurnId.random(),
                "cursor",
                List.of(new TurnStreamSnapshot.Message(
                        new TurnStreamCall(1, "attempt", ItemId.random()), text, kind, Optional.empty())),
                Optional.empty(),
                Optional.empty());
        DesktopState initial = DesktopState.initial();
        return new DesktopState(
                initial.connection(),
                initial.navigation(),
                new ThreadState(
                        List.of(workspace), Optional.of(workspace), List.of(), Optional.empty(), Optional.empty()),
                new TranscriptState(List.of(), 0, true, Optional.of(stream), true, List.of()),
                initial.interaction());
    }

    private static ItemHistoryEntry history(
            WorkspaceId workspace,
            ItemId id,
            MessageRole role,
            boolean truncated,
            String text,
            List<AttachmentRef> attachments,
            List<DocumentReference> files) {
        return new ItemHistoryEntry(
                id,
                TurnId.random(),
                1,
                "message",
                Optional.of(role),
                text,
                Optional.of(DocumentReference.message(workspace, id, "body")),
                truncated,
                Instant.EPOCH,
                attachments,
                files);
    }

    private static List<ItemHistoryEntry> documentHistory(WorkspaceId workspace) {
        ItemHistoryEntry user =
                history(workspace, ItemId.random(), MessageRole.USER, false, "普通用户消息", List.of(), List.of());
        ItemHistoryEntry assistant =
                history(workspace, ItemId.random(), MessageRole.ASSISTANT, false, "普通助手消息", List.of(), List.of());
        ItemId source = ItemId.random();
        AttachmentRef attachment = new AttachmentRef("e".repeat(64), "text/markdown", "设计文档.md", 80);
        ItemHistoryEntry document = history(
                workspace,
                source,
                MessageRole.ASSISTANT,
                true,
                "较长的设计说明摘要",
                List.of(attachment),
                List.of(DocumentReference.file(workspace, source, "file:0")));
        return List.of(user, assistant, document);
    }

    private static ScrollEvent upwardScroll() {
        return new ScrollEvent(
                ScrollEvent.SCROLL,
                20,
                20,
                20,
                20,
                false,
                false,
                false,
                false,
                false,
                false,
                0,
                30,
                0,
                30,
                ScrollEvent.HorizontalTextScrollUnits.NONE,
                0,
                ScrollEvent.VerticalTextScrollUnits.NONE,
                0,
                0,
                null);
    }

    private static final class ReadingFixture implements AutoCloseable {
        private final AtomicReference<DesktopState> observed = new AtomicReference<>();
        private final DesktopPresenter presenter = new DesktopPresenter(
                notifications -> {
                    throw new IllegalStateException("验收不建立网络连接");
                },
                Runnable::run,
                Clock.systemUTC());
        private final StackPane host = new StackPane();
        private final VBox progress = new VBox();
        private final ShellSidePanels panels =
                new ShellSidePanels(new BorderPane(), new VBox(), progress, new Button());
        private final ShellWebSurfaces surfaces =
                new ShellWebSurfaces(presenter, progress, host, new ListView<>(), panels);
        private final Stage stage = new Stage();

        private ReadingFixture() {
            presenter.subscribe(observed::set);
            Scene scene = new Scene(host, 760, 500);
            DesktopStylesheets.apply(scene);
            stage.setScene(scene);
            stage.show();
            ((WebSurfaceHost) host.getChildren().getFirst()).useFallback();
        }

        private void render(String tail) {
            DesktopState seed = state(tail, TurnStreamKind.TEXT_DELTA);
            var messages = IntStream.range(0, 80)
                    .mapToObj(index -> new TurnStreamSnapshot.Message(
                            new TurnStreamCall(1, "attempt-" + index, ItemId.random()),
                            index == 79 ? tail : "历史正文 " + index,
                            TurnStreamKind.TEXT_DELTA,
                            Optional.empty()))
                    .toList();
            var stream =
                    new TurnStreamSnapshot(TurnId.random(), "cursor", messages, Optional.empty(), Optional.empty());
            surfaces.render(new DesktopState(
                    seed.connection(),
                    seed.navigation(),
                    seed.threads(),
                    new TranscriptState(List.of(), 0, following(), Optional.of(stream), true, List.of()),
                    seed.interaction()));
            layout();
        }

        private void layout() {
            host.applyCss();
            host.layout();
        }

        private boolean following() {
            return observed.get().transcript().following();
        }

        private ScrollBar bar() {
            return host.lookup("#transcriptSummary").lookupAll(".scroll-bar").stream()
                    .map(ScrollBar.class::cast)
                    .filter(bar -> bar.getOrientation() == Orientation.VERTICAL)
                    .findFirst()
                    .orElseThrow();
        }

        private Button latest() {
            return host.lookupAll(".button").stream()
                    .map(Button.class::cast)
                    .filter(button -> button.getText().equals("回到最新消息"))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() {
            surfaces.close();
            panels.close();
            stage.close();
            try {
                presenter.close();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
