package com.javaclaw.desktop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopVisualContractTest {
    @Test
    void originalDesignTokensRemainByteIdenticalToLegacyBaseline() throws Exception {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("chat.css", "bf6a431e8ce2ab0f7e795a8dcb7784f82d80b5a095c040017e902eb61fdb68e3"),
                Map.entry("controls.css", "32f620aec3e529f32e7a1ffb80a47ac6830a572fba66aed0c827fefea713c62b"),
                Map.entry("knowledge-center.css", "5181cc3b3a367d6c5696c28b4fb986061832b661b0ae3dd824dd0559b8b2f658"),
                Map.entry("mcp-settings.css", "b6a4f8d02954465c09546a6bdeb636539cafff1c3fa9f5633f057a7fadebe178"),
                Map.entry("memory-center.css", "6a12b9ac98fefbaaee8745041b6fa3dbb8c106617d065c6dc672327c135b23f5"),
                Map.entry("plugins.css", "4cd2ae16c6b275fddfe4b38f5730aa9a48b64fb7a18deab833a4488e442d1034"),
                Map.entry("schedule.css", "2129226de45c84b82aa4093b9d00a50806611132837b89bdd0982cdab0b530e9"),
                Map.entry("sdd-task.css", "722d4b2a4cc16be1ade61e879bf936b27ffd473b05c5c0c5a036de7e3fdb7a9a"),
                Map.entry("skill-center.css", "21760b1496da373041aa82ce45ae716567a1f3e6837248738f753b69c833a914"),
                Map.entry("workflow-center.css", "9ef8ab792ad99fbe264ea3317fc88c0047ec46fb58d746697d30dfefe18bdab2"));
        for (var entry : expected.entrySet()) {
            assertEquals(entry.getValue(), hash(entry.getKey()), entry.getKey());
        }
    }

    @Test
    void chatKeepsOriginalSidebarHeaderComposerAndAddsTheProgressProjection() throws Exception {
        try (var input = getClass().getResourceAsStream("/fxml/main.fxml")) {
            assertNotNull(input);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(input);
            assertEquals("BorderPane", document.getDocumentElement().getTagName());
            assertEquals(0, document.getElementsByTagName("SplitPane").getLength());
        }
        try (var input = getClass().getResourceAsStream("/fxml/main.fxml")) {
            String fxml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String style : List.of("sidebar-root", "chat-top-bar", "input-bar", "send-button", "sb-new-btn")) {
                assertTrue(fxml.contains(style), style);
            }
            assertTrue(fxml.contains("progressPanel"));
            assertTrue(fxml.contains("sidebarToggleButton"));
            assertTrue(fxml.contains("progressToggleButton"));
            assertFalse(fxml.contains("managementList"));
            assertFalse(fxml.contains("<HBox spacing=\"2\">"), "功能入口不得退化为描边网格");
            assertEquals(1, fxml.split("sidebar-nav-button,sidebar-settings-menu", -1).length - 1);
            assertEquals(12, fxml.split("styleClass=\"sidebar-settings-menu-item\"", -1).length - 1);
            assertEquals(2, fxml.split("styleClass=\"sidebar-settings-menu-separator\"", -1).length - 1);
            assertTrue(fxml.contains("popupSide=\"TOP\""));
            for (String action : List.of(
                    "openProviders",
                    "openKnowledge",
                    "openMemory",
                    "openSkills",
                    "openAutomation",
                    "openSchedules",
                    "openPlugins",
                    "openMcp",
                    "openProfiles",
                    "openSites",
                    "openInstructions",
                    "openWorktrees")) {
                assertTrue(fxml.contains("onAction=\"#" + action + "\""), action);
            }
        }
        assertEquals("emerald", DesktopTheme.DEFAULT_ID);
        assertEquals(9, DesktopTheme.choices().size());
    }

    @Test
    void stableControlsExposeIdsAndAccessibleNamesWhileV4CssUsesLegacyTokens() throws Exception {
        try (var input = getClass().getResourceAsStream("/fxml/main.fxml")) {
            assertNotNull(input);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(input);
            Set<String> ids = Set.of(
                    "workspaceBox",
                    "newWorkspaceButton",
                    "newThreadButton",
                    "searchField",
                    "threadList",
                    "managementMenu",
                    "sidebarToggleButton",
                    "progressToggleButton",
                    "threadActionMenu",
                    "themeMenu",
                    "shortcutHelpButton",
                    "settingsButton",
                    "transcriptList",
                    "removeAttachmentButton",
                    "attachButton",
                    "composer",
                    "sendButton",
                    "profileBox",
                    "interruptButton");
            for (String id : ids) {
                var values = document.getElementsByTagName("*");
                boolean found = false;
                for (int index = 0; index < values.getLength(); index++) {
                    var attributes = values.item(index).getAttributes();
                    var actual = attributes.getNamedItem("fx:id");
                    if (actual != null && id.equals(actual.getNodeValue())) {
                        var accessible = attributes.getNamedItem("accessibleText");
                        assertNotNull(accessible, id + " 缺少 accessibleText");
                        assertFalse(accessible.getNodeValue().isBlank(), id);
                        if ("removeAttachmentButton".equals(id)) {
                            assertTrue(attributes
                                    .getNamedItem("styleClass")
                                    .getNodeValue()
                                    .contains("jc-btn-danger"));
                        }
                        found = true;
                        break;
                    }
                }
                assertTrue(found, id);
            }
            var values = document.getElementsByTagName("Button");
            for (int index = 0; index < values.getLength(); index++) {
                var attributes = values.item(index).getAttributes();
                var text = attributes.getNamedItem("text");
                if (text != null
                        && List.of("删除", "清除", "卸载", "移除", "中断").stream().anyMatch(text.getNodeValue()::contains)) {
                    assertTrue(
                            attributes.getNamedItem("styleClass").getNodeValue().contains("jc-btn-danger"));
                }
            }
        }
        assertEquals(List.of("chat", "controls", "desktop"), DesktopTheme.baseStylesheets());
        assertTrue(ManagementPageSpec.all().stream()
                .flatMap(value -> value.stylesheets().stream())
                .noneMatch(DesktopTheme.baseStylesheets()::contains));
        try (var input = getClass().getResourceAsStream("/css/desktop.css")) {
            assertNotNull(input);
            String css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(css.matches("(?s).*#[0-9A-Fa-f]{3,8}.*"), "v4 适配 CSS 不应绕过原设计令牌");
            assertFalse(css.contains("rgb("), "v4 适配 CSS 不应绕过原设计令牌");
            assertTrue(css.contains("-jc-surface-page"));
            assertTrue(css.contains("-jc-text-muted"));
            assertTrue(css.contains("-jc-on-brand"));
            assertTrue(css.contains("-jc-tint-terra-fg"));
            assertTrue(css.contains(".management-fixed-actions"));
            assertTrue(css.contains("-jc-surface-card"));
            assertTrue(css.contains(".sidebar-list-scroll .list-cell:filled:selected"));
            assertTrue(css.contains(".transcript-list .list-cell:filled:hover"));
            assertTrue(css.contains(".sidebar-navigation .sidebar-nav-button"));
            assertTrue(css.contains(".sidebar-navigation .sidebar-settings-menu:showing"));
            assertTrue(css.contains(".context-menu .menu-item.sidebar-settings-menu-item"));
            assertTrue(css.contains("-fx-max-height: 25;"));
            assertTrue(css.contains(".context-menu .menu-item.sidebar-settings-menu-separator"));
            assertTrue(css.contains(".management-page-settings .management-rail.settings-left-pane"));
            assertTrue(css.contains(".settings-navigation-list .list-cell:filled:selected"));
        }
    }

    @Test
    void composerKeepsInputModeAttachmentsAndActionsInsideOneCard() throws Exception {
        try (var input = getClass().getResourceAsStream("/fxml/main.fxml")) {
            assertNotNull(input);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(input);
            var card = nodeWithId(document, "composerCard");
            assertNotNull(card);
            for (String id : List.of(
                    "composer",
                    "attachmentPreview",
                    "composerToolbar",
                    "attachButton",
                    "profileBox",
                    "interruptButton",
                    "sendButton")) {
                var control = nodeWithId(document, id);
                assertNotNull(control, id);
                assertTrue(isAncestor(card, control), id + " 必须位于一体化输入卡片内");
            }
            var profile = nodeWithId(document, "profileBox");
            assertTrue(profile.getAttributes()
                    .getNamedItem("styleClass")
                    .getNodeValue()
                    .contains("composer-select"));
            assertEquals(
                    "选择模式", profile.getAttributes().getNamedItem("promptText").getNodeValue());
            assertEquals(
                    "选择运行模式",
                    profile.getAttributes().getNamedItem("accessibleText").getNodeValue());
            assertEquals("104", profile.getAttributes().getNamedItem("minWidth").getNodeValue());
            assertEquals(
                    "112", profile.getAttributes().getNamedItem("prefWidth").getNodeValue());
            assertEquals("120", profile.getAttributes().getNamedItem("maxWidth").getNodeValue());
        }
        try (var input = getClass().getResourceAsStream("/fxml/main.fxml")) {
            assertNotNull(input);
            String fxml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(fxml.contains("<Label text=\"模式\""), "模式不应再作为输入框下方的独立表单行");
            assertFalse(fxml.contains("desktop-mode-bar"));
        }
        try (var input = getClass().getResourceAsStream("/css/desktop.css")) {
            assertNotNull(input);
            String css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(css.contains(".composer-card"));
            assertTrue(css.contains(".composer-toolbar"));
            assertTrue(css.contains(".composer-profile-select"));
            assertTrue(css.contains(".composer-card:focus-within"));
        }
    }

    private static org.w3c.dom.Node nodeWithId(org.w3c.dom.Document document, String id) {
        var values = document.getElementsByTagName("*");
        for (int index = 0; index < values.getLength(); index++) {
            var attribute = values.item(index).getAttributes().getNamedItem("fx:id");
            if (attribute != null && id.equals(attribute.getNodeValue())) {
                return values.item(index);
            }
        }
        return null;
    }

    private static boolean isAncestor(org.w3c.dom.Node ancestor, org.w3c.dom.Node value) {
        for (var current = value.getParentNode(); current != null; current = current.getParentNode()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private String hash(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/css/" + name)) {
            assertNotNull(input);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
}
