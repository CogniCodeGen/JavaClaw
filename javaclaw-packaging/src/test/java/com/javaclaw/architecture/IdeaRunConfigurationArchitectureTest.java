package com.javaclaw.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeaRunConfigurationArchitectureTest {
    private static final String SOCKET = "$PROJECT_DIR$/.javaclaw/idea/app-server-v6.sock";

    @Test
    void compoundLaunchesTheSharedAppServerAndDesktopConfigurations() throws Exception {
        Element configuration = configuration("JavaClaw_Local_Debug.run.xml");
        NodeList targets = configuration.getElementsByTagName("toRun");
        Map<String, String> targetTypes = new HashMap<>();
        for (int index = 0; index < targets.getLength(); index++) {
            Element target = (Element) targets.item(index);
            targetTypes.put(target.getAttribute("name"), target.getAttribute("type"));
        }

        assertEquals("JavaClaw 一键启动（前后端）", configuration.getAttribute("name"));
        assertEquals("CompoundRunConfigurationType", configuration.getAttribute("type"));
        assertEquals(Map.of("JavaClaw App Server", "Application", "JavaClaw Desktop", "Application"), targetTypes);
    }

    @Test
    void appServerConfigurationPinsModuleMainClassSocketAndDataV6() throws Exception {
        Element configuration = configuration("JavaClaw_App_Server.run.xml");

        assertApplication(
                configuration, "JavaClaw App Server", "javaclaw-app-server", "com.javaclaw.server.AppServerMain");
        assertEquals("--socket \"" + SOCKET + "\"", option(configuration, "PROGRAM_PARAMETERS"));
        String virtualMachine = option(configuration, "VM_PARAMETERS");
        assertTrue(virtualMachine.contains("--enable-native-access=ALL-UNNAMED"));
        assertTrue(virtualMachine.contains("-Djavaclaw.data.root=\"$PROJECT_DIR$/.javaclaw/idea/data-v6\""));
        assertTrue(virtualMachine.contains("-Djavaclaw.log.dir=\"$PROJECT_DIR$/.javaclaw/idea/data-v6/logs\""));
    }

    @Test
    void desktopConfigurationUsesTheSameSocketAndBoundedStartupWait() throws Exception {
        Element configuration = configuration("JavaClaw_Desktop.run.xml");

        assertApplication(
                configuration,
                "JavaClaw Desktop",
                "javaclaw-desktop",
                "com.javaclaw.desktop.shell.JavaClawDesktopMain");
        String virtualMachine = option(configuration, "VM_PARAMETERS");
        assertEquals(
                Set.of("ALL-UNNAMED", "javafx.graphics", "javafx.media", "javafx.web"),
                nativeAccessModules(virtualMachine));
        assertTrue(virtualMachine.contains("-Djavaclaw.server.socket=\"" + SOCKET + "\""));
        assertTrue(virtualMachine.contains("-Djavaclaw.server.startup-timeout=PT15S"));
    }

    private static Set<String> nativeAccessModules(String virtualMachine) {
        String prefix = "--enable-native-access=";
        return Arrays.stream(virtualMachine.split("\\s+"))
                .filter(argument -> argument.startsWith(prefix))
                .flatMap(argument ->
                        Arrays.stream(argument.substring(prefix.length()).split(",")))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void assertApplication(Element configuration, String name, String moduleName, String mainClass) {
        assertEquals(name, configuration.getAttribute("name"));
        assertEquals("Application", configuration.getAttribute("type"));
        assertEquals(
                moduleName,
                ((Element) configuration.getElementsByTagName("module").item(0)).getAttribute("name"));
        assertEquals(mainClass, option(configuration, "MAIN_CLASS_NAME"));
        assertEquals("$PROJECT_DIR$", option(configuration, "WORKING_DIRECTORY"));
        Element make = namedElement(configuration.getElementsByTagName("option"), "Make");
        assertEquals("true", make.getAttribute("enabled"));
    }

    private static Element configuration(String fileName) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder()
                .parse(repositoryRoot().resolve(".run").resolve(fileName).toFile());
        return (Element) document.getElementsByTagName("configuration").item(0);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(".run"))
                    && Files.isDirectory(candidate.resolve("javaclaw-packaging"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("JavaClaw repository root cannot be located");
    }

    private static String option(Element configuration, String name) {
        return namedElement(configuration.getElementsByTagName("option"), name).getAttribute("value");
    }

    private static Element namedElement(NodeList elements, String name) {
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (element.getAttribute("name").equals(name)) {
                return element;
            }
        }
        throw new IllegalArgumentException("IDEA run configuration field does not exist: " + name);
    }
}
