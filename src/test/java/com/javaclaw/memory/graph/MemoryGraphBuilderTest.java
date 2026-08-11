package com.javaclaw.memory.graph;

import com.javaclaw.memory.model.EntityNode;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.store.MemoryStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryGraphBuilderTest {

    @Test
    void 空存储关闭存储与读取异常都安全降级为空图() {
        assertEquals(MemoryGraph.empty(),
                MemoryGraphBuilder.build(null, MemoryGraphBuilder.Options.defaults()));

        FakeStore closed = new FakeStore(false);
        assertEquals(MemoryGraph.empty(),
                MemoryGraphBuilder.build(closed, MemoryGraphBuilder.Options.defaults()));

        FakeStore broken = new FakeStore(true);
        broken.failure = new IllegalStateException("读取失败");
        assertEquals(MemoryGraph.empty(),
                MemoryGraphBuilder.build(broken, MemoryGraphBuilder.Options.defaults()));
        assertEquals(MemoryGraph.empty(), MemoryGraphBuilder.build(broken, null));
    }

    @Test
    void 构建事实来源实体与去重后的语义边() {
        Episode sharedEpisode = episode(null, 7, "  用户问题  ", "答".repeat(260));
        Episode isolatedEpisode = episode("ep-isolated", 8, null, "   ");
        Episode fallbackEpisode = episode(null, 9, "独立情景", null);

        EntityNode project = entity(null, 11, " JavaClaw ", null);
        EntityNode tool = entity("tool-1", 12, "JDK", " tool ");
        EntityNode isolated = entity("isolated", 13, "Spring", "framework");
        EntityNode fallbackEntity = entity(null, 14, "虚拟线程", null);

        Fact first = fact(null, 1, null,
                "  这是一个很长的事实   包含多余空白  ", new float[]{1, 0, 0, 0}, 30);
        first.hitCount = -2;
        first.mergeCount = 1;
        first.source = sharedEpisode;
        first.about = new ArrayList<>(List.of(project, project));
        first.about.add(null);
        first.about.add(entity(null, 0, null, "topic"));
        first.about.add(entity(null, 0, "   ", "topic"));

        Fact second = fact("fact-2", 2, "  ", null,
                new float[]{1, 0, 0, 0}, 20);
        second.hitCount = 2;
        second.mergeCount = 3;
        second.source = sharedEpisode;
        second.about = null;

        Fact third = fact("fact-3", 3, " project ", "短事实", null, 10);
        third.about = List.of(tool);

        Fact superseded = fact("old", 4, "历史", "旧事实",
                new float[]{1, 0, 0, 0}, 40);
        superseded.superseded = true;
        Fact contested = fact("contested", 5, "争议", "争议事实",
                new float[]{1, 0, 0, 0}, 50);
        contested.contested = true;

        FakeStore store = new FakeStore(true);
        store.facts = List.of(first, second, third, superseded, contested);
        store.entities = listWithNulls(
                null,
                entity(null, 0, null, "topic"),
                entity(null, 0, " ", "topic"),
                project,
                isolated,
                fallbackEntity);
        store.episodes = listWithNulls(null, sharedEpisode, isolatedEpisode, fallbackEpisode);
        store.hits.put(first, List.of(
                scored(first, 1.0f),
                scored(second, 0.91234f),
                scored(superseded, 0.9f),
                scored(second, 0.88f)));
        store.hits.put(second, List.of(
                scored(first, 0.91234f),
                scored(second, 1.0f)));

        MemoryGraph graph = MemoryGraphBuilder.build(store,
                new MemoryGraphBuilder.Options(20, 0.75, 4, true, 12));

        assertEquals(3, countNodes(graph, "fact"));
        assertEquals(3, countNodes(graph, "episode"));
        assertEquals(4, countNodes(graph, "entity"));
        assertEquals(2, countEdges(graph, "source"));
        assertEquals(2, countEdges(graph, "about"));
        assertEquals(1, countEdges(graph, "semantic"));
        assertTrue(graph.edges().stream()
                .anyMatch(edge -> edge.kind().equals("semantic") && edge.weight() == 0.912));

        MemoryGraph.Node firstNode = node(graph, "fact:e1");
        assertEquals("这是一个很长的事实 包含…", firstNode.label());
        assertEquals("未分类", firstNode.group());
        assertEquals(1, firstNode.weight());
        assertEquals("", node(graph, "fact:fact-2").detail());
        assertEquals("project", node(graph, "fact:fact-3").group());
        assertTrue(node(graph, "episode:e7").detail().contains("…"));
        assertEquals("topic", node(graph, "entity:n11: JavaClaw ").group());
        assertEquals(2, node(graph, "entity:n11: JavaClaw ").weight());
    }

    @Test
    void 节点窗口按更新时间截断且可关闭语义检索() {
        Fact old = fact("old", 1, "a", "旧", new float[]{1, 0, 0, 0}, 1);
        Fact newest = fact("new", 2, "b", "新", new float[]{1, 0, 0, 0}, 3);
        Fact middle = fact("middle", 3, "c", "中", new float[]{1, 0, 0, 0}, 2);
        newest.source = episode("source", 1, "来源", "");
        newest.about = List.of(entity("entity", 1, "实体", "topic"));

        FakeStore store = new FakeStore(true);
        store.facts = List.of(old, newest, middle);
        store.entities = List.of(entity("orphan", 2, "孤立实体", null));
        store.episodes = List.of(episode("orphan", 2, "孤立情景", null));

        MemoryGraph graph = MemoryGraphBuilder.build(store,
                new MemoryGraphBuilder.Options(1, 0.5, 1, false, 20));

        assertEquals(1, countNodes(graph, "fact"));
        assertEquals("新", node(graph, "fact:new").label());
        assertEquals(0, countEdges(graph, "semantic"));
        assertEquals(0, store.searchCalls);
        assertFalse(graph.nodes().stream().anyMatch(node -> node.id().equals("fact:old")));
    }

    @Test
    void 无事实时按预算纳入合法孤立节点() {
        FakeStore store = new FakeStore(true);
        store.entities = listWithNulls(
                null,
                entity(null, 0, null, null),
                entity(null, 0, " ", null),
                entity("one", 1, "实体一", null),
                entity(null, 2, "实体二", "topic"),
                entity("three", 3, "实体三", "topic"));
        store.episodes = listWithNulls(
                null,
                episode("ep", 1, "不会进入预算", null));

        MemoryGraph graph = MemoryGraphBuilder.build(store,
                new MemoryGraphBuilder.Options(2, 0.8, 2, true, 8));

        assertEquals(2, graph.nodes().size());
        assertTrue(graph.nodes().stream().allMatch(node -> node.type().equals("entity")));
        assertTrue(graph.edges().isEmpty());
    }

    private static Fact fact(
            String id, long entityId, String section, String text, float[] embedding, long updatedAt) {
        Fact fact = new Fact(section, text, embedding);
        fact.id = id;
        fact.entityId = entityId;
        fact.updatedAt = updatedAt;
        return fact;
    }

    private static Episode episode(
            String id, long entityId, String userInput, String assistantReply) {
        Episode episode = new Episode("session", userInput, assistantReply);
        episode.id = id;
        episode.entityId = entityId;
        return episode;
    }

    private static EntityNode entity(
            String id, long entityId, String name, String type) {
        EntityNode entity = new EntityNode(name, type);
        entity.id = id;
        entity.entityId = entityId;
        return entity;
    }

    private static MemoryStore.Scored<Fact> scored(Fact fact, float score) {
        return new MemoryStore.Scored<>(fact, score);
    }

    @SafeVarargs
    private static <T> List<T> listWithNulls(T... values) {
        List<T> result = new ArrayList<>();
        java.util.Collections.addAll(result, values);
        return result;
    }

    private static long countNodes(MemoryGraph graph, String type) {
        return graph.nodes().stream().filter(node -> node.type().equals(type)).count();
    }

    private static long countEdges(MemoryGraph graph, String kind) {
        return graph.edges().stream().filter(edge -> edge.kind().equals(kind)).count();
    }

    private static MemoryGraph.Node node(MemoryGraph graph, String id) {
        return graph.nodes().stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static final class FakeStore extends MemoryStore {
        private final boolean open;
        private List<Fact> facts = List.of();
        private List<EntityNode> entities = List.of();
        private List<Episode> episodes = List.of();
        private final Map<Fact, List<Scored<Fact>>> hits = new IdentityHashMap<>();
        private RuntimeException failure;
        private int searchCalls;

        private FakeStore(boolean open) {
            super(Path.of("."), 4, "graph-test");
            this.open = open;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public List<Fact> allFacts() {
            if (failure != null) throw failure;
            return facts;
        }

        @Override
        public List<EntityNode> allEntities() {
            return entities;
        }

        @Override
        public List<Episode> allEpisodes() {
            return episodes;
        }

        @Override
        public List<Scored<Fact>> searchFacts(float[] query, int topK, double threshold) {
            searchCalls++;
            for (Fact fact : facts) {
                if (fact.embedding == query) return hits.getOrDefault(fact, List.of());
            }
            return List.of();
        }
    }
}
