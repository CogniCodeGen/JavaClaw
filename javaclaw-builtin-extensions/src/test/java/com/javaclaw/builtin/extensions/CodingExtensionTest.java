package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.builtin.contracts.CodingSchemas;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingExtensionTest {
    @Test
    void 工具仅调用绑定平台端口且管理命令不执行项目() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        AtomicInteger invoked = new AtomicInteger();
        ExtensionResponse expected = new ExtensionResponse(new CanonicalPayload("{}"), 0);
        support.workspaceExecution = () -> {
            invoked.incrementAndGet();
            return expected;
        };
        try (CodingExtension extension = new CodingExtension()) {
            var started = support.start(extension);
            List<ExtensionContributions.Tool> tools = started.contributions().stream()
                    .filter(ExtensionContributions.Tool.class::isInstance)
                    .map(ExtensionContributions.Tool.class::cast)
                    .toList();
            assertEquals(12, tools.size());
            assertEquals(
                    expected,
                    started.tool(
                            "file_read",
                            support.request(
                                    "file_read",
                                    new CodingContracts.FileRead("README.md", 0, 100),
                                    Optional.empty(),
                                    0)));
            assertEquals(1, invoked.get());
            var commands = started.contributions().stream()
                    .filter(ExtensionContributions.Command.class::isInstance)
                    .map(ExtensionContributions.Command.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals(Set.of("environment/update", "toolchain/install"), commands.operations());
            assertFalse(commands.operations().contains("dependencies_prepare"));
            assertTrue(tools.stream().anyMatch(tool -> tool.contributionId().equals("dependencies_prepare")));
        }
    }

    @Test
    void 缺少权威端口时不能通过默认上下文执行文件读取() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        try (CodingExtension extension = new CodingExtension()) {
            var started = support.start(extension);
            assertThrows(
                    SecurityException.class,
                    () -> started.tool(
                            "file_read",
                            support.request(
                                    "file_read",
                                    new CodingContracts.FileRead("README.md", 0, 100),
                                    Optional.empty(),
                                    0)));
        }
    }

    @Test
    void 所有CodingSchema可加载且JSON拒绝冒充Turn权限的未知字段() {
        CanonicalJson json = new CanonicalJson();
        try (CodingExtension extension = new CodingExtension()) {
            assertEquals(31, extension.schemas().size());
            for (String name : CodingSchemas.names()) {
                assertTrue(CodingSchemas.read(name).json().contains("\"additionalProperties\":false"));
                assertTrue(CodingSchemas.read(name).json().contains("\"$id\":\"" + CodingSchemas.id(name) + "\""));
            }
            CodingResults.OutputRead request = new CodingResults.OutputRead("resource-1", 100, 64);
            assertEquals(request, json.decode(json.encode(request), CodingResults.OutputRead.class));
            assertThrows(
                    com.javaclaw.protocol.ProtocolException.class,
                    () -> json.decode(
                            new CanonicalPayload(
                                    "{\"maxBytes\":64,\"offsetBytes\":100,\"resourceId\":\"resource-1\",\"turnId\":\"spoofed\"}"),
                            CodingResults.OutputRead.class));
        }
    }
}
