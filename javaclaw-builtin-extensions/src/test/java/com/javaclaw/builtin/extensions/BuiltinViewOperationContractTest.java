package com.javaclaw.builtin.extensions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinViewOperationContractTest {
    @Test
    void 所有内置页面数据源和操作名称都符合发布的ViewSchema协议() throws Exception {
        OperationContract contract = operationContract();
        for (ExtensionBundle bundle : BuiltinExtensions.create()) {
            try (bundle) {
                var started = new BuiltinExtensionTestSupport().start(bundle);
                for (var contribution : started.contributions()) {
                    if (contribution instanceof ExtensionContributions.View view) {
                        assertOperations(view.view(), contract);
                    }
                }
            }
        }
    }

    private static void assertOperations(ViewSchema view, OperationContract contract) {
        view.dataSources().forEach(source -> contract.require(source.query(), view.viewId()));
        view.graphBrowsing().values().forEach(browsing -> contract.require(browsing.neighborsQuery(), view.viewId()));
        for (ViewSchema.Node node : view.nodes()) {
            for (ViewAction action : actions(node)) {
                contract.require(action.command(), view.viewId());
            }
        }
    }

    private static List<ViewAction> actions(ViewSchema.Node node) {
        return switch (node) {
            case ViewSchema.Form form -> List.of(form.submit());
            case ViewSchema.ListView list -> list.actions();
            case ViewSchema.Table table -> table.actions();
            case ViewSchema.Card card -> card.actions();
            default -> List.of();
        };
    }

    private static OperationContract operationContract() throws Exception {
        try (var input =
                BuiltinViewOperationContractTest.class.getResourceAsStream("/schema/view-schema-v3.schema.json")) {
            assertNotNull(input);
            CanonicalJson json = new CanonicalJson();
            Map<?, ?> schema =
                    json.decode(json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)), Map.class);
            Map<?, ?> definitions = assertInstanceOf(Map.class, schema.get("$defs"));
            Map<?, ?> operation = assertInstanceOf(Map.class, definitions.get("operation"));
            String pattern = assertInstanceOf(String.class, operation.get("pattern"));
            int maximumLength =
                    assertInstanceOf(Number.class, operation.get("maxLength")).intValue();
            return new OperationContract(Pattern.compile(pattern), maximumLength);
        }
    }

    private record OperationContract(Pattern pattern, int maximumLength) {
        private void require(String operation, String viewId) {
            assertTrue(
                    operation.length() <= maximumLength
                            && pattern.matcher(operation).matches(),
                    () -> "内置页面 " + viewId + " 发布了不符合协议的操作名: " + operation);
        }
    }
}
