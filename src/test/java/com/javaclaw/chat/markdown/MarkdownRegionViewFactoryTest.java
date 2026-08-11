package com.javaclaw.chat.markdown;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.image.ImageViewerFactory;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class MarkdownRegionViewFactoryTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final MarkdownParagraphRenderer.RenderStyleSnapshot STYLE =
            new MarkdownParagraphRenderer.RenderStyleSnapshot(
                    14.5, 1.65, "\"System\", sans-serif", "\"SF Mono\", monospace");
    private static final SpringFxmlLoader LOADER =
            new SpringFxmlLoader(new DefaultListableBeanFactory());
    private static final FxDispatcher FX = new FxDispatcher();
    private static final MarkdownRegionViewFactory FACTORY = new MarkdownRegionViewFactory(
            LOADER, FX, new ImageViewerFactory(LOADER, FX));

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void codeCardUsesFxmlNodesAndReceivesDynamicContent() throws Exception {
        Region card = callFx(() -> FACTORY.createCodeCard(
                "int answer = 42;", "java", STYLE));

        Label language = find(card, "codeLanguage", Label.class);
        Button copy = find(card, "copyCode", Button.class);
        TextFlow flow = find(card, "codeFlow", TextFlow.class);
        assertEquals("java", callFx(language::getText));
        assertEquals("复制", callFx(copy::getText));
        assertEquals("int answer = 42;", callFx(() ->
                ((Text) flow.getChildren().getFirst()).getText()));
    }

    @Test
    void ruleAndImageRetainFxmlDefinedStructure() throws Exception {
        Region rule = callFx(FACTORY::createHorizontalRule);
        Region image = callFx(() -> FACTORY.createImage("not-a-valid-image-url"));

        assertTrue(callFx(() -> containsStyleClass(rule, "md-hr")));
        assertNotNull(find(image, "markdownImage", ImageView.class));
        assertTrue(callFx(() -> containsStyleClass(image, "md-image")));
    }

    private static <N extends Node> N find(Node root, String id, Class<N> type)
            throws Exception {
        return callFx(() -> {
            Node node = findById(root, id);
            assertTrue(type.isInstance(node), "未找到 FXML 节点 #" + id);
            return type.cast(node);
        });
    }

    private static Node findById(Node node, String id) {
        if (id.equals(node.getId())) return node;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node match = findById(child, id);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static boolean containsStyleClass(Node node, String styleClass) {
        if (node.getStyleClass().contains(styleClass)) return true;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsStyleClass(child, styleClass)) return true;
            }
        }
        return false;
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
        return result.get();
    }
}
