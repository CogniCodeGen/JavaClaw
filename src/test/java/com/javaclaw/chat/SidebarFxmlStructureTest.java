package com.javaclaw.chat;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarFxmlStructureTest {

    private static final String FXML_NAMESPACE = "http://javafx.com/fxml/1";

    @Test
    void declaresSplitControllersStateIdsAndEventEntrypoints() throws Exception {
        assertView("/fxml/chat/sidebar-view.fxml", SidebarController.class,
                Set.of("root", "workspaceCombo", "sessionList", "profile"),
                Set.of("#onWorkspaceSelected", "#onNewChatRequested"));
        assertView("/fxml/chat/sidebar-session-list.fxml", SidebarSessionListController.class,
                Set.of("root", "sessionList", "searchField", "manageButton",
                        "batchDeleteRow", "sessionCountLabel", "emptyState"),
                Set.of("#toggleBatchMode", "#onBatchDelete"));
        assertView("/fxml/chat/sidebar-profile.fxml", SidebarProfileController.class,
                Set.of("root", "profileMenu", "profileMenuButton", "skillNavBadge",
                        "scheduleNavBadge", "taskNavBadge"),
                Set.of("#toggleProfileMenu", "#onOpenSettingsRequested"));
        assertView("/fxml/chat/sidebar-session-cell.fxml", SidebarSessionCellController.class,
                Set.of("root", "conversationRow", "groupRow", "titleLabel", "checkBox"),
                Set.of("#onRowClicked", "#onDeleteRequested"));
    }

    private static void assertView(
            String path,
            Class<?> controller,
            Set<String> expectedIds,
            Set<String> expectedHandlers
    ) throws Exception {
        URL resource = SidebarFxmlStructureTest.class.getResource(path);
        assertNotNull(resource, path);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(resource.openStream());
        assertEquals(controller.getName(),
                document.getDocumentElement().getAttributeNS(FXML_NAMESPACE, "controller"));

        Set<String> ids = IntStream.range(0, document.getElementsByTagName("*").getLength())
                .mapToObj(document.getElementsByTagName("*")::item)
                .map(node -> node.getAttributes().getNamedItemNS(FXML_NAMESPACE, "id"))
                .filter(Objects::nonNull)
                .map(org.w3c.dom.Node::getNodeValue)
                .collect(Collectors.toSet());
        assertTrue(ids.containsAll(expectedIds), path + " missing ids: " + expectedIds);

        String xml = Files.readString(Path.of(resource.toURI()));
        for (String handler : expectedHandlers) {
            assertTrue(xml.contains(handler), path + " missing handler " + handler);
        }
    }
}
