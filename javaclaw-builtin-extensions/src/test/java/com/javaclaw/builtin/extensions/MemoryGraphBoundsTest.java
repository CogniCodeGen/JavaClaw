package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryGraphBoundsTest {
    @Test
    void independentBrowserWindowsAreBoundedAndCannotOverwriteEachOther() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        seed(support);
        var initial = new MemoryV3Contracts.GraphWindow("", true, List.of());
        var first = nodes(support, memory, "", initial);
        assertEquals(200, first.rows().size());
        assertTrue(!first.hasMore());
        var admitted = java.util.stream.IntStream.range(0, 500)
                .mapToObj(index -> "n" + String.format("%04d", index))
                .toList();
        var expanded = new MemoryV3Contracts.GraphWindow("", true, admitted);
        var page = nodes(support, memory, "", expanded);
        String oldCursor = page.nextCursor();
        List<Object> all = new ArrayList<>(page.rows());
        while (page.hasMore()) {
            page = nodes(support, memory, page.nextCursor(), expanded);
            all.addAll(page.rows());
        }
        assertEquals(500, all.size());
        var otherClient = new MemoryV3Contracts.GraphWindow("", false, List.of());
        var active = nodes(support, memory, "", otherClient);
        assertTrue(active.rows().stream().noneMatch(value -> value.json().contains("n0000")));
        assertEquals(200, nodes(support, memory, "", initial).rows().size());
        assertThrows(IllegalArgumentException.class, () -> nodes(support, memory, oldCursor, otherClient));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MemoryV3Contracts.GraphWindow(
                        "",
                        true,
                        java.util.stream.IntStream.range(0, 501)
                                .mapToObj(Integer::toString)
                                .toList()));
    }

    @Test
    void neighborApiReturnsAtMostFiftyAndExposesRemainingCursor() throws Exception {
        var support = new BuiltinExtensionTestSupport();
        var memory = support.start(new MemoryExtension());
        seed(support);
        var page = support.decode(
                memory.query(support.request(
                        "graph/neighbors",
                        new MemoryV3Contracts.GraphNeighbors("n0000", "", true, 50),
                        Optional.empty(),
                        0)),
                MemoryV3Contracts.GraphPage.class);
        assertEquals(50, page.graph().nodes().size());
        assertTrue(page.hasMore());
        var next = support.decode(
                memory.query(support.request(
                        "graph/neighbors",
                        new MemoryV3Contracts.GraphNeighbors("n0000", page.nextCursor(), true, 50),
                        Optional.empty(),
                        0)),
                MemoryV3Contracts.GraphPage.class);
        assertEquals(50, next.graph().nodes().size());
        Set<String> firstIds = page.graph().nodes().stream()
                .map(MemoryV3Contracts.GraphNode::id)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(next.graph().nodes().stream().noneMatch(node -> firstIds.contains(node.id())));
    }

    private static ViewQueryResult nodes(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started memory,
            String cursor,
            MemoryV3Contracts.GraphWindow window)
            throws Exception {
        return support.decode(
                memory.query(support.request(
                        "view.graph.nodes",
                        new ViewQueryRequest(
                                "graphNodes",
                                Map.of(
                                        "graphWindow",
                                        support.payloads.encode(window).json()),
                                cursor,
                                200,
                                Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
    }

    private static void seed(BuiltinExtensionTestSupport support) throws Exception {
        support.store.inTransaction(MemoryStoreAccess.ID, transaction -> {
            Set<String> replacements = new LinkedHashSet<>();
            for (int index = 0; index < 560; index++) {
                String id = "n" + String.format("%04d", index);
                var memory = new MemoryContracts.Memory(
                        id,
                        1,
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "记忆 " + id,
                        Set.of(),
                        false,
                        Optional.empty(),
                        BuiltinExtensionTestSupport.NOW,
                        BuiltinExtensionTestSupport.NOW);
                transaction.put(
                        MemoryCollectionNames.memories(support.workspaceId), id, 0, support.payloads.encode(memory));
                if (index >= 200) {
                    replacements.add(id);
                }
            }
            var effect = new MemoryV3Contracts.Effectivity(
                    "n0000",
                    1,
                    MemoryV3Contracts.SemanticState.SUPERSEDED,
                    Optional.empty(),
                    Optional.empty(),
                    "",
                    replacements);
            transaction.put(MemorySemantics.effects(support.workspaceId), "n0000", 0, support.payloads.encode(effect));
            return null;
        });
    }
}
