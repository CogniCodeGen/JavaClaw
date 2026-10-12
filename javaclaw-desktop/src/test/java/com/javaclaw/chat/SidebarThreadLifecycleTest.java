package com.javaclaw.chat;

import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.spring.ApplicationContexts;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true")
class SidebarThreadLifecycleTest {
    @BeforeAll static void toolkit() throws Exception {
        var started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); } catch (IllegalStateException running) { started.countDown(); }
        assertTrue(started.await(5, TimeUnit.SECONDS));
    }

    @Test void archivedAndChildThreadsRemainFilterableAndLifecycleActionsKeepTheirIdentity(@TempDir Path path) throws Exception {
        try (var context = ApplicationContexts.createRoot(new DataRoot(path.resolve("data")))) {
            ViewHandle<VBox> view = fx(() -> context.getBean(SpringFxmlLoader.class)
                    .load(getClass().getResource("/fxml/chat/sidebar-view.fxml")));
            try {
                fx(() -> {
                    var sidebar = view.controller(SidebarController.class);
                    var list = view.controller(SidebarSessionListController.class);
                    List<String> called = new ArrayList<>();
                    sidebar.setOnSwitchSession(id -> called.add("switch:" + id));
                    sidebar.setOnArchiveSession(id -> called.add("archive:" + id));
                    sidebar.setOnResumeSession(id -> called.add("resume:" + id));
                    sidebar.setOnForkSession(id -> called.add("fork:" + id));
                    sidebar.setOnInspectSession(id -> called.add("inspect:" + id));
                    sidebar.setOnDeleteSession(id -> called.add("delete:" + id));
                    var parent = session("parent", "Parent", 0);
                    var child = session("child", "Worker", 1);
                    child.setParentThreadId("parent"); child.setArchived(true);
                    sidebar.addSession(parent, true); sidebar.addSession(child, false);
                    for (int age = 2; age <= 8; age++) sidebar.addSession(session("old-" + age, "Past " + age, age), false);
                    sidebar.insertSessionAtTop(new ChatSession("blank", null, null, List.of()), false);
                    assertEquals(10, sidebar.getSessionCount());
                    List<SidebarSessionItem.Conversation> visible = conversations(list);
                    assertEquals("新的对话", visible.getFirst().title());
                    var worker = visible.stream().filter(row -> row.id().equals("child")).findFirst().orElseThrow();
                    assertTrue(worker.archived()); assertEquals("parent", worker.parentThreadId());
                    list.activate("child"); list.activate("child");
                    list.resume("child"); list.archive("parent"); list.fork("parent"); list.inspect("child"); list.delete("old-8");
                    assertEquals(List.of("switch:child", "resume:child", "archive:parent", "fork:parent", "inspect:child", "delete:old-8"), called);
                    child.setArchived(false); sidebar.updateLifecycle(child);
                    sidebar.updateLifecycle(session("missing", "ignored", 0));
                    sidebar.updateSessionTitle("child", "  Worker resumed  ");
                    sidebar.updateSessionTitle("missing", "ignored");
                    TextField search = field(list, "searchField", TextField.class);
                    search.setText(" WORKER ");
                    assertEquals(1, conversations(list).size());
                    assertFalse(conversations(list).getFirst().archived());
                    search.setText("not found");
                    assertEquals("没有匹配的会话", field(list, "emptyTitle", Label.class).getText());
                    search.clear(); sidebar.selectSession("parent"); sidebar.selectSession("missing");
                    assertEquals("parent", sidebar.getSelectedSessionId());
                    sidebar.removeSession("old-8"); assertEquals(9, sidebar.getSessionCount());
                    search.setText("Parent"); sidebar.clearSessions();
                    assertEquals(0, sidebar.getSessionCount()); assertTrue(search.getText().isEmpty());
                    assertNull(sidebar.getSelectedSessionId());
                    assertEquals("还没有会话", field(list, "emptyTitle", Label.class).getText());
                    return null;
                });
            } finally { fx(() -> { view.close(); return null; }); }
        }
    }

    @Test void batchSelectionDoesNotSwitchThreadAndClosingUnregistersCallbacks(@TempDir Path path) throws Exception {
        try (var context = ApplicationContexts.createRoot(new DataRoot(path.resolve("data")))) {
            ViewHandle<VBox> view = fx(() -> context.getBean(SpringFxmlLoader.class)
                    .load(getClass().getResource("/fxml/chat/sidebar-session-list.fxml")));
            fx(() -> {
                var list = view.controller(SidebarSessionListController.class);
                List<String> switches = new ArrayList<>();
                list.setOnSwitchSession(switches::add);
                list.addSession(session("a", "A", 0), true); list.addSession(session("b", "B", 0), false);
                field(list, "manageButton", Button.class).fire();
                list.activate("b");
                assertTrue(switches.isEmpty()); assertEquals("a", list.getSelectedSessionId());
                assertTrue(conversations(list).stream().filter(row -> row.id().equals("b")).findFirst().orElseThrow().checked());
                assertFalse(field(list, "batchDeleteButton", Button.class).isDisable());
                list.activate("b"); assertTrue(field(list, "batchDeleteButton", Button.class).isDisable());
                Button all = field(list, "selectAllButton", Button.class);
                all.fire(); assertTrue(conversations(list).stream().allMatch(SidebarSessionItem.Conversation::checked));
                all.fire(); assertFalse(conversations(list).stream().anyMatch(SidebarSessionItem.Conversation::checked));
                field(list, "manageButton", Button.class).fire();
                list.activate("b"); assertEquals(List.of("b"), switches);
                list.setOnArchiveSession(switches::add); list.setOnResumeSession(switches::add);
                list.setOnForkSession(switches::add); list.setOnInspectSession(switches::add); list.setOnDeleteSession(switches::add);
                view.close(); list.close();
                list.archive("a"); list.resume("a"); list.fork("a"); list.inspect("a"); list.delete("a");
                assertEquals(List.of("b"), switches);
                assertTrue(field(list, "sessionList", ListView.class).getItems().isEmpty());
                return null;
            });
        }
    }

    private static ChatSession session(String id, String title, int daysAgo) {
        return new ChatSession(id, title, LocalDateTime.now().minusDays(daysAgo), List.of());
    }
    private static List<SidebarSessionItem.Conversation> conversations(SidebarSessionListController list) throws Exception {
        return field(list, "sessionList", ListView.class).getItems().stream()
                .filter(SidebarSessionItem.Conversation.class::isInstance).map(SidebarSessionItem.Conversation.class::cast).toList();
    }
    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(owner));
    }
    private static <T> T fx(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); Platform.runLater(future); return future.get(10, TimeUnit.SECONDS);
    }
}
