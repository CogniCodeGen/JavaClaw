package com.javaclaw.desktop.settings;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementCenterSearchTest {
    @Test
    void 返回设置页面复用滚动容器并保留阅读位置() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                ScrollPane original = (ScrollPane) fixture.window.getScene().lookup(".settings-scroll-pane");
                original.setVvalue(0.65);
                fixture.center.show(fixture.owner, "connection");
                fixture.center.show(fixture.owner, "appearance");

                ScrollPane restored = (ScrollPane) fixture.window.getScene().lookup(".settings-scroll-pane");
                assertSame(original, restored);
                assertEquals(0.65, restored.getVvalue(), 0.001);
            }
        });
    }

    @Test
    void 搜索标题优先于说明且清空后恢复原目录顺序() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                List<?> original = List.copyOf(fixture.navigation.getItems());
                assertTitleMatch(fixture, "定时任务", "schedule");
                assertTitleMatch(fixture, "记忆", "memory");
                assertTitleMatch(fixture, "技能", "skill");

                fixture.search.setText("  AGENT  ");
                fixture.assertFirst("roles");
                fixture.assertPage("Agent Studio");
                fixture.search.setText("任务");
                fixture.assertFirst("jobs");
                assertTrue(fixture.indexOf("schedule") < fixture.indexOf("unattended-grants"));
                fixture.search.setText("工作");
                fixture.assertFirst("workspace");
                assertTrue(fixture.indexOf("workflow") < fixture.indexOf("worktrees"));
                assertTrue(fixture.indexOf("worktrees") < fixture.indexOf("jobs"));

                fixture.search.clear();
                assertEquals(original, fixture.navigation.getItems());
                fixture.assertPage("后台任务");
            }
        });
    }

    @Test
    void 逐字输入完整标题时进入同名页且不会覆盖后续手动选择() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.center.show(fixture.owner, "learning");
                fixture.search.setText("记");
                fixture.assertFirst("memory");
                fixture.assertPage("学习策略");

                fixture.search.setText("记忆");

                fixture.assertPage("记忆");
                fixture.navigation.getSelectionModel().select(fixture.indexOf("learning"));
                fixture.assertPage("学习策略");
                fixture.search.setText("记忆");
                fixture.assertPage("学习策略");
                fixture.search.clear();
                fixture.search.setText("记忆");
                fixture.assertPage("记忆");
            }
        });
    }

    @Test
    void 有未保存草稿时精确搜索不切页且清空后恢复原选择和草稿() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                Object originalSelection =
                        fixture.navigation.getSelectionModel().getSelectedItem();
                ToggleButton theme = fixture.window.getScene().getRoot().lookupAll(".theme-card").stream()
                        .filter(ToggleButton.class::isInstance)
                        .map(ToggleButton.class::cast)
                        .filter(button -> !button.isSelected())
                        .findFirst()
                        .orElseThrow();
                theme.fire();

                fixture.search.setText("记忆");

                fixture.assertFirst("memory");
                fixture.assertPage("外观");
                assertTrue(theme.isSelected());
                fixture.search.setText("无匹配页面");
                assertTrue(fixture.navigation.getItems().isEmpty());
                fixture.assertPage("外观");
                fixture.search.clear();
                assertSame(
                        originalSelection,
                        fixture.navigation.getSelectionModel().getSelectedItem());
                assertTrue(theme.isSelected());
            }
        });
    }

    private static void assertTitleMatch(Fixture fixture, String title, String key) {
        fixture.search.setText(title);
        fixture.assertFirst(key);
        fixture.assertPage(title);
    }

    /** 使用内存窗口偏好和未连接 SDK；搜索测试不会启动服务或写入用户配置。 */
    private static final class Fixture implements AutoCloseable {
        private final DesktopPresenter desktop = new DesktopPresenter(
                ignored -> {
                    throw new IOException("搜索测试不连接服务");
                },
                Platform::runLater,
                Clock.systemUTC());
        private final Stage owner = new Stage();
        private final ManagementCenterWindow center = new ManagementCenterWindow(
                new DesktopAppearanceManager(new MemoryAppearanceStore()),
                SdkManagementSettingsGateways.create(desktop),
                new MemoryWindowStore());
        private final Stage window;
        private final TextField search;
        private final ListView<?> navigation;

        private Fixture() {
            owner.setScene(new Scene(new VBox(), 600, 400));
            owner.show();
            center.show(owner, "appearance");
            window = Window.getWindows().stream()
                    .filter(Stage.class::isInstance)
                    .map(Stage.class::cast)
                    .filter(stage -> stage.getOwner() == owner && stage.isShowing())
                    .findFirst()
                    .orElseThrow();
            window.getScene().getRoot().applyCss();
            search = (TextField) window.getScene().lookup(".settings-search-field");
            navigation = (ListView<?>) window.getScene().lookup(".management-navigation-list");
        }

        private int indexOf(String key) {
            return java.util.stream.IntStream.range(0, navigation.getItems().size())
                    .filter(index -> navigation.getItems().get(index).toString().contains("key=" + key + ","))
                    .findFirst()
                    .orElseThrow();
        }

        private void assertFirst(String key) {
            assertEquals(0, indexOf(key));
        }

        private void assertPage(String title) {
            Label breadcrumb = (Label) window.getScene().lookup(".settings-crumb-cur");
            assertEquals(title, breadcrumb.getText());
            assertTrue(
                    navigation.getSelectionModel().getSelectedItem().toString().contains("title=" + title + ","));
        }

        @Override
        public void close() {
            center.dispose();
            owner.hide();
            try {
                desktop.close();
            } catch (Exception failure) {
                throw new AssertionError("关闭搜索测试客户端失败", failure);
            }
        }
    }

    private static final class MemoryAppearanceStore implements AppearancePreferenceStore {
        @Override
        public AppearancePreferences load() {
            return AppearancePreferences.defaults();
        }

        @Override
        public void save(AppearancePreferences preferences) {}
    }

    private static final class MemoryWindowStore implements ManagementWindowPreferenceStore {
        @Override
        public ManagementWindowPreferences load() {
            return ManagementWindowPreferences.defaults();
        }

        @Override
        public void save(ManagementWindowPreferences preferences) {}
    }
}
