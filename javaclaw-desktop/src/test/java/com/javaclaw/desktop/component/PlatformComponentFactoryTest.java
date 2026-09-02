package com.javaclaw.desktop.component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformComponentFactoryTest {
    @Test
    void 页面卡片动作反馈和列表行共享同一套视觉语义() {
        FxTestSupport.run(() -> {
            PlatformComponentFactory components = new PlatformComponentFactory();

            VBox page = components.page("扩展页面");
            VBox section = components.section("运行状态", new Label("正常"));
            page.getChildren().add(section);
            assertTrue(page.getStyleClass().contains("platform-page"));
            assertTrue(section.getStyleClass().containsAll(List.of("jc-card", "platform-section-card")));
            assertTrue(
                    page.getChildren().stream().allMatch(node -> node.getStyle().isEmpty()));

            List<Button> actions = List.of(
                    components.action("主要", ActionStyle.PRIMARY, ActionSize.NORMAL),
                    components.action("柔和", ActionStyle.SOFT, ActionSize.COMPACT),
                    components.action("弱化", ActionStyle.GHOST, ActionSize.NORMAL),
                    components.action("危险", ActionStyle.DANGER, ActionSize.COMPACT));
            assertTrue(
                    actions.stream().allMatch(button -> button.getStyleClass().contains("jc-btn")));
            assertTrue(actions.get(1).getStyleClass().contains("jc-btn-sm"));
            assertTrue(actions.get(3).getStyleClass().contains("jc-btn-danger"));

            for (FeedbackKind kind : FeedbackKind.values()) {
                VBox feedback = components.feedback(kind, "状态", "这是简单说明。");
                assertTrue(feedback.getStyleClass().contains("platform-feedback"));
                assertTrue(feedback.getAccessibleText().contains("这是简单说明"));
            }

            assertCellsReuseTextAndDetailStructure(components);
            assertThrows(IllegalArgumentException.class, () -> components.page("  "));
            assertThrows(NullPointerException.class, () -> components.section("分区", (Node) null));
        });
    }

    private static void assertCellsReuseTextAndDetailStructure(PlatformComponentFactory components) {
        ListCell<String> textCell = components.textCell(String::toUpperCase);
        update(textCell, "thread", false);
        assertEquals("THREAD", textCell.getText());
        update(textCell, null, false);
        assertNull(textCell.getText());

        ListCell<String> detailCell = components.detailCell(value -> "标题 " + value, value -> "详情 " + value);
        update(detailCell, "one", false);
        VBox graphic = (VBox) detailCell.getGraphic();
        assertEquals("标题 one", ((Label) graphic.getChildren().getFirst()).getText());
        assertEquals("详情 one", ((Label) graphic.getChildren().get(1)).getText());
        assertTrue(graphic.getStyleClass().contains("platform-detail-cell"));
        update(detailCell, "one", true);
        assertNull(detailCell.getGraphic());
        assertNull(detailCell.getAccessibleText());
    }

    private static void update(ListCell<?> cell, Object item, boolean empty) {
        Method method = Arrays.stream(cell.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("updateItem"))
                .filter(candidate -> !candidate.isBridge())
                .findFirst()
                .orElseThrow();
        try {
            method.setAccessible(true);
            method.invoke(cell, item, empty);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }
}
