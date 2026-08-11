package com.javaclaw.chat;

import com.javaclaw.ui.javafx.loop.LoopStatusController;
import com.javaclaw.ui.javafx.knowledge.KnowledgeActionItemController;
import com.javaclaw.ui.javafx.knowledge.KnowledgeCheckItemController;
import com.javaclaw.ui.javafx.knowledge.KnowledgeHeaderItemController;
import com.javaclaw.ui.javafx.knowledge.KnowledgeMenuController;
import com.javaclaw.ui.javafx.theme.ThemeMenuController;
import com.javaclaw.ui.javafx.theme.ThemeMenuEntryController;
import javafx.fxml.FXML;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;

import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFxmlStructureTest {

    private static final String FXML_NAMESPACE = "http://javafx.com/fxml/1";

    @Test
    void mainChatDeclaresEveryInjectedNodeAndEventEntrypoint() throws Exception {
        Document document = document("/fxml/chat/chat-view.fxml");
        assertEquals(ChatViewController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, ChatViewController.class);

        Set<String> handlers = eventHandlers(document);
        Set<String> controllerHandlers = Set.of(ChatViewController.class.getDeclaredMethods())
                .stream()
                .filter(method -> method.isAnnotationPresent(FXML.class))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertTrue(controllerHandlers.containsAll(handlers),
                "Controller 缺少 @FXML 事件方法: " + handlers);
        assertEquals(Set.of("toggleSidebar", "openTaskManager", "openSettingsRequested",
                "onClearHistory"), handlers);
    }

    @Test
    void thinkingPanelDeclaresEveryInjectedNode() throws Exception {
        Document document = document("/fxml/chat/thinking-panel.fxml");
        assertEquals(ThinkingPanelController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, ThinkingPanelController.class);
        assertTrue(eventHandlers(document).isEmpty());
    }

    @Test
    void markdownBubbleDeclaresEveryInjectedNode() throws Exception {
        Document document = document("/fxml/chat/markdown-bubble.fxml");
        assertEquals(MarkdownBubbleController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, MarkdownBubbleController.class);
        assertTrue(eventHandlers(document).isEmpty());
    }

    @Test
    void composerDeclaresEveryInjectedNodeAndEventEntrypoint() throws Exception {
        Document document = document("/fxml/chat/chat-composer.fxml");
        assertEquals(ChatComposerController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, ChatComposerController.class);
        assertEquals(Set.of("addAttachmentRequested", "sendOrStopRequested"),
                eventHandlers(document));
    }

    @Test
    void modeBarDeclaresEveryInjectedNodeAndEventEntrypoint() throws Exception {
        Document document = document("/fxml/chat/chat-mode-bar.fxml");
        assertEquals(ChatModeController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, ChatModeController.class);
        assertEquals(Set.of("openWorkflowCenterRequested", "openTaskManagerRequested"),
                eventHandlers(document));
    }

    @Test
    void sessionViewDeclaresEveryInjectedNodeAndEventEntrypoint() throws Exception {
        Document document = document("/fxml/chat/chat-session-view.fxml");
        assertEquals(ChatSessionController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, ChatSessionController.class);
        assertEquals(Set.of("newMessagesRequested"), eventHandlers(document));
    }

    @Test
    void assistantMessageDeclaresEveryInjectedNodeAndEventEntrypoint() throws Exception {
        Document document = document("/fxml/chat/assistant-message.fxml");
        assertEquals(AssistantMessageController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, AssistantMessageController.class);
        assertEquals(Set.of("adoptRequested", "regenerateRequested", "quoteRequested",
                "showMoreMenu", "copyRequested", "saveRequested", "deleteRequested"),
                eventHandlers(document));
    }

    @Test
    void expandableMarkdownBlockDeclaresInjectedNodesAndActions() throws Exception {
        Document document = document("/fxml/chat/expandable-markdown-block.fxml");
        assertEquals(ExpandableMarkdownBlockController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, ExpandableMarkdownBlockController.class);
        assertEquals(Set.of("collapseRequested", "copyRequested"), eventHandlers(document));
    }

    @Test
    void staticMessageRowDeclaresEveryInjectedNode() throws Exception {
        Document document = document("/fxml/chat/chat-message-row.fxml");
        assertEquals(ChatMessageRowController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, ChatMessageRowController.class);
        assertTrue(eventHandlers(document).isEmpty());
    }

    @Test
    void loopDecisionDeclaresEveryInjectedNodeAndAction() throws Exception {
        Document document = document("/fxml/chat/loop-decision.fxml");
        assertEquals(LoopDecisionController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, LoopDecisionController.class);
        assertEquals(Set.of("continueRequested", "stopRequested"), eventHandlers(document));
    }

    @Test
    void clarificationCardDeclaresEveryInjectedNode() throws Exception {
        Document document = document("/fxml/chat/clarification-card.fxml");
        assertEquals(ClarificationCardController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, ClarificationCardController.class);
        assertTrue(eventHandlers(document).isEmpty());
    }

    @Test
    void loopStatusDeclaresEveryInjectedNode() throws Exception {
        Document document = document("/fxml/chat/loop-status.fxml");
        assertEquals(LoopStatusController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, LoopStatusController.class);
        assertTrue(eventHandlers(document).isEmpty());
    }

    @Test
    void workspaceSwitchOverlayDeclaresEveryInjectedNode() throws Exception {
        Document document = document("/fxml/chat/workspace-switch-overlay.fxml");
        assertEquals(WorkspaceSwitchOverlayController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, WorkspaceSwitchOverlayController.class);
        assertTrue(eventHandlers(document).isEmpty());
    }

    @Test
    void themeMenuDeclaresEveryInjectedNodeAndAction() throws Exception {
        Document document = document("/fxml/chat/theme-menu.fxml");
        assertEquals(ThemeMenuController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, ThemeMenuController.class);
        assertEquals(Set.of("showing"), eventHandlers(document));
    }

    @Test
    void themeMenuEntryDeclaresEveryInjectedNodeAndAction() throws Exception {
        Document document = document("/fxml/chat/theme-menu-entry.fxml");
        assertEquals(ThemeMenuEntryController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, ThemeMenuEntryController.class);
        assertEquals(Set.of("selected"), eventHandlers(document));
    }

    @Test
    void knowledgeMenuDeclaresEveryInjectedNodeAndActions() throws Exception {
        Document document = document("/fxml/chat/knowledge-menu.fxml");
        assertEquals(KnowledgeMenuController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, KnowledgeMenuController.class);
        assertEquals(Set.of("showing", "hidden"), eventHandlers(document));
    }

    @Test
    void knowledgeMenuEntriesDeclareControllersAndActions() throws Exception {
        assertMenuEntry("/fxml/chat/knowledge-check-item.fxml",
                KnowledgeCheckItemController.class, Set.of("selectionChanged"));
        assertMenuEntry("/fxml/chat/knowledge-action-item.fxml",
                KnowledgeActionItemController.class, Set.of("requested"));
        assertMenuEntry("/fxml/chat/knowledge-header-item.fxml",
                KnowledgeHeaderItemController.class, Set.of());
        assertControllerlessFxml("/fxml/chat/knowledge-separator.fxml", Set.of());
    }

    @Test
    void attachmentPreviewDeclaresEveryInjectedNodeAndEventEntrypoint() throws Exception {
        Document document = document("/fxml/chat/attachment-preview-item.fxml");
        assertEquals(AttachmentPreviewItemController.class.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, AttachmentPreviewItemController.class);
        assertEquals(Set.of("removeRequested"), eventHandlers(document));
    }

    @Test
    void markdownRegionsKeepFixedStructureInFxml() throws Exception {
        assertControllerlessFxml("/fxml/chat/markdown/code-card.fxml",
                Set.of("codeLanguage", "copyCode", "codeFlow"));
        assertControllerlessFxml("/fxml/chat/markdown/horizontal-rule.fxml", Set.of());
        assertControllerlessFxml("/fxml/chat/markdown/image.fxml", Set.of("markdownImage"));
        assertControllerlessFxml("/fxml/chat/markdown/table.fxml",
                Set.of("tableRoot", "interactiveGrid", "fallbackArea"));
        assertControllerlessFxml("/fxml/chat/markdown/table-cell.fxml",
                Set.of("cellSurface", "cellText"));
    }

    private static void assertControllerlessFxml(String path, Set<String> requiredIds)
            throws Exception {
        Document document = document(path);
        assertEquals("", document.getDocumentElement()
                .getAttributeNS(FXML_NAMESPACE, "controller"), path);
        Set<String> ids = IntStream.range(0, document.getElementsByTagName("*").getLength())
                .mapToObj(document.getElementsByTagName("*")::item)
                .map(node -> node.getAttributes().getNamedItemNS(FXML_NAMESPACE, "id"))
                .filter(Objects::nonNull)
                .map(org.w3c.dom.Node::getNodeValue)
                .collect(Collectors.toSet());
        assertTrue(ids.containsAll(requiredIds), path + " 缺少 fx:id: " + requiredIds);
        assertTrue(eventHandlers(document).isEmpty(), path + " 不应绕过统一事件装配");
    }

    private static void assertMenuEntry(
            String path, Class<?> controller, Set<String> handlers) throws Exception {
        Document document = document(path);
        assertEquals(controller.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));
        assertInjectedFields(document, controller);
        assertEquals(handlers, eventHandlers(document));
    }

    private static Document document(String path) throws Exception {
        URL resource = ChatFxmlStructureTest.class.getResource(path);
        assertNotNull(resource, path);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(resource.openStream());
    }

    private static void assertInjectedFields(Document document, Class<?> controller) {
        Set<String> ids = IntStream.range(0, document.getElementsByTagName("*").getLength())
                .mapToObj(document.getElementsByTagName("*")::item)
                .map(node -> node.getAttributes().getNamedItemNS(FXML_NAMESPACE, "id"))
                .filter(Objects::nonNull)
                .map(org.w3c.dom.Node::getNodeValue)
                .collect(Collectors.toSet());
        for (Field field : controller.getDeclaredFields()) {
            if (!field.isAnnotationPresent(FXML.class)) continue;
            String fieldName = field.getName();
            boolean declared = ids.contains(fieldName)
                    || fieldName.endsWith("Controller")
                    && ids.contains(fieldName.substring(
                    0, fieldName.length() - "Controller".length()));
            assertTrue(declared, "FXML 缺少 @FXML 字段: " + fieldName);
        }
    }

    private static Set<String> eventHandlers(Document document) {
        Set<String> handlers = new HashSet<>();
        var elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            NamedNodeMap attributes = elements.item(index).getAttributes();
            for (int attributeIndex = 0; attributeIndex < attributes.getLength(); attributeIndex++) {
                String value = attributes.item(attributeIndex).getNodeValue();
                if (value.startsWith("#")) {
                    handlers.add(value.substring(1));
                }
            }
        }
        return handlers;
    }
}
