package com.javaclaw.memory.store;

import com.javaclaw.memory.model.AgentCheckpoint;
import com.javaclaw.memory.model.ChangeLogEntry;
import com.javaclaw.memory.model.EntityNode;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.KnowledgeChunk;
import com.javaclaw.memory.model.MemoryRoot;
import com.javaclaw.memory.model.Persona;
import org.eclipse.store.gigamap.jvector.VectorIndex;
import org.eclipse.store.gigamap.jvector.VectorIndexConfiguration;
import org.eclipse.store.gigamap.jvector.VectorIndices;
import org.eclipse.store.gigamap.jvector.VectorSearchResult;
import org.eclipse.store.gigamap.jvector.VectorSimilarityFunction;
import org.eclipse.store.gigamap.jvector.Vectorizer;
import org.eclipse.store.gigamap.types.GigaMap;
import org.eclipse.store.gigamap.types.ScoredSearchResult;
import org.eclipse.store.storage.embedded.types.EmbeddedStorage;
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 记忆存储基座 —— 一个目录对应一个 EclipseStore 对象图(一个工作区库或全局库)。
 *
 * <p>统一承载语义/情景/知识/实体/变更日志/工作记忆检查点/人格,各 GigaMap 挂 JVector 向量索引。</p>
 *
 * <p><b>并发模型</b>:所有写(add/update/remove/store)经单写线程串行化,避免对象图并发改写;
 * 读(向量检索)在调用线程直接执行(GigaMap 读安全)。</p>
 *
 * <p><b>变更日志</b>:每次结构性变更追加 {@link ChangeLogEntry},作为备份的替代审计轨。</p>
 *
 * <p>嵌入向量由上层(服务层)预计算后写入实体字段;本类的向量器只读取该字段,不调用嵌入 API。</p>
 *
 * @author JavaClaw
 */
public class MemoryStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private static final String IDX = "embedding";

    private final Path dir;
    private final int dimension;
    private final String label;

    private EmbeddedStorageManager mgr;
    private MemoryRoot root;

    private VectorIndex<Fact> factIndex;
    private VectorIndex<Episode> episodeIndex;
    private VectorIndex<KnowledgeChunk> knowledgeIndex;

    /** 单写线程:所有变更经此串行提交 */
    private ExecutorService writer;

    public MemoryStore(Path dir, int dimension, String label) {
        this.dir = dir;
        this.dimension = dimension;
        this.label = label;
    }

    // ==================== 检索结果 ====================

    /** 带分数的检索命中(只读视图) */
    public record Scored<E>(E entity, float score) {}

    // ==================== 向量器(命名静态类,可随索引持久化/重建) ====================

    static final class FactVectorizer extends Vectorizer<Fact> {
        @Override public float[] vectorize(Fact e) { return e.embedding; }
    }

    static final class EpisodeVectorizer extends Vectorizer<Episode> {
        @Override public float[] vectorize(Episode e) { return e.embedding; }
    }

    static final class KnowledgeVectorizer extends Vectorizer<KnowledgeChunk> {
        @Override public float[] vectorize(KnowledgeChunk e) { return e.embedding; }
    }

    // ==================== 生命周期 ====================

    /** 启动/恢复存储,确保根对象与三个向量索引就绪。 */
    public synchronized void open() {
        if (mgr != null) return;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memory-writer-" + label);
            t.setDaemon(true);
            return t;
        });
        this.mgr = EmbeddedStorage.start(dir);
        MemoryRoot r = mgr.root();
        if (r == null) {
            r = new MemoryRoot();
            mgr.setRoot(r);
            mgr.storeRoot();
            log.info("[{}] 新建记忆存储: {}", label, dir);
        } else {
            log.info("[{}] 已恢复记忆存储: {} (facts={}, episodes={}, knowledge={})",
                    label, dir, r.facts.size(), r.episodes.size(), r.knowledge.size());
        }
        this.root = r;

        // 旧库迁移：pending 暂存区为反序列化后新增字段，缺失时补建并落盘（无向量索引）。
        boolean migrated = false;
        if (root.pendingFacts == null) { root.pendingFacts = GigaMap.New(); migrated = true; }
        if (root.pendingEpisodes == null) { root.pendingEpisodes = GigaMap.New(); migrated = true; }
        if (migrated) {
            mgr.store(root);
            log.info("[{}] 已补建 pending 暂存区（旧库迁移）", label);
        }

        this.factIndex = ensureIndex(root.facts, new FactVectorizer());
        this.episodeIndex = ensureIndex(root.episodes, new EpisodeVectorizer());
        this.knowledgeIndex = ensureIndex(root.knowledge, new KnowledgeVectorizer());
        log.info("[{}] 向量索引就绪 (dim={}, COSINE)", label, dimension);
    }

    /** 获取或创建某 GigaMap 的向量索引(首次 register+add,重开 get)。 */
    private <E> VectorIndex<E> ensureIndex(GigaMap<E> map, Vectorizer<? super E> vectorizer) {
        VectorIndices<E> vis = map.index().get(VectorIndices.Category());
        if (vis == null) {
            vis = map.index().register(VectorIndices.Category());
        }
        VectorIndex<E> idx = vis.get(IDX);
        if (idx == null) {
            VectorIndexConfiguration cfg = VectorIndexConfiguration.forSmallDataset(
                    dimension, VectorSimilarityFunction.COSINE);
            idx = vis.add(IDX, cfg, vectorizer);
        }
        return idx;
    }

    @Override
    public synchronized void close() {
        if (writer != null) {
            // 先排空在途写任务再关存储：shutdown() 只拒收新任务不等待执行中任务，
            // 不等待就 mgr.shutdown() 会让在途记忆写入丢失
            writer.shutdown();
            try {
                if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("[{}] 写线程 5 秒内未排空，可能丢失在途写入", label);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writer = null;
        }
        if (mgr != null) {
            mgr.shutdown();
            mgr = null;
        }
        log.info("[{}] 记忆存储已关闭", label);
    }

    // ==================== 写协调 ====================

    /** 在单写线程上执行变更并阻塞等待完成;异常包装上抛。 */
    private void write(Runnable task) {
        ExecutorService w = this.writer;
        if (w == null) {
            throw new IllegalStateException("[" + label + "] 记忆存储已关闭，拒绝写入");
        }
        try {
            w.submit(task).get();
        } catch (Exception e) {
            throw new RuntimeException("[" + label + "] 记忆写入失败", e);
        }
    }

    /** 在单写线程上执行有返回值的变更并阻塞等待结果(用于 get-or-create 等需原子读改写的场景)。 */
    private <T> T writeCall(java.util.concurrent.Callable<T> task) {
        ExecutorService w = this.writer;
        if (w == null) {
            throw new IllegalStateException("[" + label + "] 记忆存储已关闭，拒绝写入");
        }
        try {
            return w.submit(task).get();
        } catch (Exception e) {
            throw new RuntimeException("[" + label + "] 记忆写入失败", e);
        }
    }

    /** 已在写线程内部调用,不再 submit。 */
    private void logInternal(String op, String type, String id, String actor, String detail) {
        root.changeLog.add(new ChangeLogEntry(System.currentTimeMillis(), op, type, id, actor, detail));
        root.changeLog.store();
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    // ==================== 事实(语义记忆) ====================

    /** 新增事实(嵌入须已写入 {@link Fact#embedding})。 */
    public void addFact(Fact f, String actor) {
        write(() -> {
            if (f.id == null) f.id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            if (f.createdAt == 0) f.createdAt = now;
            f.updatedAt = now;
            f.entityId = root.facts.add(f);
            root.facts.store();
            logInternal("ADD", "Fact", f.id, actor, trunc(f.text));
        });
    }

    /** 原地更新事实(通过 GigaMap.update 通知索引重建),mutator 内修改字段。 */
    public void updateFact(Fact f, java.util.function.Consumer<Fact> mutator, String actor) {
        write(() -> {
            root.facts.update(f, x -> {
                mutator.accept(x);
                x.updatedAt = System.currentTimeMillis();
            });
            root.facts.store();
            logInternal("UPDATE", "Fact", f.id, actor, trunc(f.text));
        });
    }

    /**
     * 蒸馏去重命中既有事实时的合并强化：{@code mergeCount++} 并刷新 {@code updatedAt}
     * (让被反复提到的事实自然变重、且稳定留在图谱时间窗内)，审计记 MERGE 而非泛化的 UPDATE。
     *
     * @param candidateText 本次被合并掉的候选事实原文(仅入审计,不改事实正文)
     */
    public void mergeFact(Fact f, String actor, String candidateText) {
        write(() -> {
            root.facts.update(f, x -> {
                x.mergeCount++;
                x.updatedAt = System.currentTimeMillis();
            });
            root.facts.store();
            logInternal("MERGE", "Fact", f.id, actor, trunc(candidateText));
        });
    }

    /** 删除事实。 */
    public void removeFact(Fact f, String actor) {
        write(() -> {
            root.facts.removeById(f.entityId);
            root.facts.store();
            logInternal("REMOVE", "Fact", f.id, actor, trunc(f.text));
        });
    }

    /** 向量 Top-K 检索事实,按分数阈值过滤。 */
    public List<Scored<Fact>> searchFacts(float[] query, int topK, double threshold) {
        return search(factIndex, query, topK, threshold);
    }

    /** 全部事实(只读快照,供 UI/蒸馏去重遍历)。 */
    public List<Fact> allFacts() {
        List<Fact> out = new ArrayList<>();
        root.facts.iterate(out::add);
        return out;
    }

    // ==================== 待嵌入事实(降级暂存,无向量索引) ====================

    /** 降级新增事实到 pending 暂存区(嵌入不可用时,纯文本落库、不进向量索引)。 */
    public void addPendingFact(Fact f, String actor) {
        write(() -> {
            if (f.id == null) f.id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            if (f.createdAt == 0) f.createdAt = now;
            f.updatedAt = now;
            f.pending = true;
            f.embedding = null; // 暂存区不持有向量
            f.entityId = root.pendingFacts.add(f);
            root.pendingFacts.store();
            logInternal("ADD_PENDING", "Fact", f.id, actor, trunc(f.text));
        });
    }

    /** 原地更新 pending 事实(仍留在暂存区,如离线编辑正文)。 */
    public void updatePendingFact(Fact f, java.util.function.Consumer<Fact> mutator, String actor) {
        write(() -> {
            root.pendingFacts.update(f, x -> {
                mutator.accept(x);
                x.updatedAt = System.currentTimeMillis();
            });
            root.pendingFacts.store();
            logInternal("UPDATE", "Fact", f.id, actor, trunc(f.text));
        });
    }

    /** 从 pending 暂存区删除事实。 */
    public void removePendingFact(Fact f, String actor) {
        write(() -> {
            root.pendingFacts.removeById(f.entityId);
            root.pendingFacts.store();
            logInternal("REMOVE", "Fact", f.id, actor, trunc(f.text));
        });
    }

    /** 全部 pending 事实(只读快照)。 */
    public List<Fact> allPendingFacts() {
        List<Fact> out = new ArrayList<>();
        root.pendingFacts.iterate(out::add);
        return out;
    }

    // ==================== 情景记忆 ====================

    public void addEpisode(Episode e, String actor) {
        write(() -> {
            if (e.id == null) e.id = UUID.randomUUID().toString();
            if (e.timestamp == 0) e.timestamp = System.currentTimeMillis();
            e.entityId = root.episodes.add(e);
            root.episodes.store();
            logInternal("ADD", "Episode", e.id, actor, trunc(e.userInput));
        });
    }

    public List<Scored<Episode>> searchEpisodes(float[] query, int topK, double threshold) {
        return search(episodeIndex, query, topK, threshold);
    }

    /** 全部情景(只读快照,供记忆图谱构建/列举)。 */
    public List<Episode> allEpisodes() {
        List<Episode> out = new ArrayList<>();
        root.episodes.iterate(out::add);
        return out;
    }

    // ==================== 待嵌入情景(降级暂存,无向量索引) ====================

    /** 降级新增情景到 pending 暂存区(嵌入不可用时,纯文本落库、不进向量索引)。 */
    public void addPendingEpisode(Episode e, String actor) {
        write(() -> {
            if (e.id == null) e.id = UUID.randomUUID().toString();
            if (e.timestamp == 0) e.timestamp = System.currentTimeMillis();
            e.pending = true;
            e.embedding = null;
            e.entityId = root.pendingEpisodes.add(e);
            root.pendingEpisodes.store();
            logInternal("ADD_PENDING", "Episode", e.id, actor, trunc(e.userInput));
        });
    }

    /** 从 pending 暂存区删除情景。 */
    public void removePendingEpisode(Episode e, String actor) {
        write(() -> {
            root.pendingEpisodes.removeById(e.entityId);
            root.pendingEpisodes.store();
            logInternal("REMOVE", "Episode", e.id, actor, trunc(e.userInput));
        });
    }

    /** 全部 pending 情景(只读快照)。 */
    public List<Episode> allPendingEpisodes() {
        List<Episode> out = new ArrayList<>();
        root.pendingEpisodes.iterate(out::add);
        return out;
    }

    // ==================== 记忆图实体节点 ====================

    /**
     * 按规范化名称 get-or-create 实体节点(整个读改写在单写线程内原子完成,遵循单写纪律)。
     * 命中既有同名实体则直接返回(不覆盖类型);否则新建并入库。embedding 暂留空(实体级语义检索为 P5)。
     *
     * @return 既有或新建的实体节点
     */
    public EntityNode getOrCreateEntity(String name, String type, String actor) {
        String norm = name == null ? "" : name.strip();
        if (norm.isEmpty()) return null;
        return writeCall(() -> {
            EntityNode[] found = {null};
            root.entities.iterate(e -> {
                if (found[0] == null && e.name != null && e.name.equalsIgnoreCase(norm)) {
                    found[0] = e;
                }
            });
            if (found[0] != null) return found[0];
            EntityNode node = new EntityNode(norm, type == null ? "topic" : type.strip());
            node.id = UUID.randomUUID().toString();
            node.entityId = root.entities.add(node);
            root.entities.store();
            logInternal("ADD", "EntityNode", node.id, actor, norm + "(" + node.type + ")");
            return node;
        });
    }

    /** 全部实体节点(只读快照,供记忆图谱构建/列举)。 */
    public List<EntityNode> allEntities() {
        List<EntityNode> out = new ArrayList<>();
        root.entities.iterate(out::add);
        return out;
    }

    // ==================== 知识库 ====================

    public void addKnowledgeChunk(KnowledgeChunk c, String actor) {
        write(() -> {
            if (c.id == null) c.id = UUID.randomUUID().toString();
            c.entityId = root.knowledge.add(c);
            root.knowledge.store();
            logInternal("ADD", "KnowledgeChunk", c.id, actor, trunc(c.docName));
        });
    }

    public List<Scored<KnowledgeChunk>> searchKnowledge(float[] query, int topK, double threshold) {
        return search(knowledgeIndex, query, topK, threshold);
    }

    /** 全部知识分块(只读快照,供关键词降级检索/列举)。 */
    public List<KnowledgeChunk> allKnowledge() {
        List<KnowledgeChunk> out = new ArrayList<>();
        root.knowledge.iterate(out::add);
        return out;
    }

    /** 删除某文档的全部分块,返回删除数量。 */
    public int removeKnowledgeByDoc(String docName, String actor) {
        int[] removed = {0};
        write(() -> {
            List<Long> ids = new ArrayList<>();
            root.knowledge.iterate(c -> {
                if (docName.equals(c.docName)) ids.add(c.entityId);
            });
            for (long id : ids) root.knowledge.removeById(id);
            if (!ids.isEmpty()) {
                root.knowledge.store();
                logInternal("REMOVE", "KnowledgeChunk", docName, actor, ids.size() + " 块");
            }
            removed[0] = ids.size();
        });
        return removed[0];
    }

    /** 清空全部知识分块。 */
    public void clearKnowledge(String actor) {
        write(() -> {
            root.knowledge.removeAll();
            root.knowledge.store();
            logInternal("CLEAR", "KnowledgeChunk", null, actor, "");
        });
    }

    /** 原地更新知识分块(通过 GigaMap.update 通知索引重建),供重建索引时回填新向量。 */
    public void updateKnowledgeChunk(KnowledgeChunk c, java.util.function.Consumer<KnowledgeChunk> mutator, String actor) {
        write(() -> {
            root.knowledge.update(c, mutator::accept);
            root.knowledge.store();
            logInternal("UPDATE", "KnowledgeChunk", c.id, actor, trunc(c.docName));
        });
    }

    // ==================== 工作记忆检查点 ====================

    public void checkpoint(String key, String messagesJson) {
        write(() -> {
            root.working.put(key, new AgentCheckpoint(key, messagesJson));
            mgr.store(root.working);
        });
    }

    public AgentCheckpoint loadCheckpoint(String key) {
        return root.working.get(key);
    }

    public void removeCheckpoint(String key) {
        write(() -> {
            if (root.working.remove(key) != null) {
                mgr.store(root.working);
                logInternal("REMOVE", "Checkpoint", key, "user", "");
            }
        });
    }

    // ==================== 人格 ====================

    public Persona getPersona() {
        return root.persona;
    }

    public void setPersona(String content, String actor) {
        write(() -> {
            if (root.persona == null) {
                root.persona = new Persona(content);
            } else {
                root.persona.content = content;
                root.persona.updatedAt = System.currentTimeMillis();
            }
            mgr.store(root.persona);
            mgr.store(root);
            logInternal("PERSONA_EDIT", "Persona", null, actor, trunc(content));
        });
    }

    /** 原地更新人格(结构化字段 + 组装正文统一在 mutator 内完成),不存在则新建。 */
    public void updatePersona(java.util.function.Consumer<Persona> mutator, String actor) {
        write(() -> {
            if (root.persona == null) root.persona = new Persona("");
            mutator.accept(root.persona);
            root.persona.updatedAt = System.currentTimeMillis();
            mgr.store(root.persona);
            mgr.store(root);
            logInternal("PERSONA_EDIT", "Persona", null, actor, trunc(root.persona.content));
        });
    }

    // ==================== 变更日志 ====================

    /** 显式追加一条审计(供上层在非结构性事件时记录)。 */
    public void appendChangeLog(String op, String type, String id, String actor, String detail) {
        write(() -> logInternal(op, type, id, actor, detail));
    }

    public List<ChangeLogEntry> recentChangeLog(int limit) {
        List<ChangeLogEntry> all = new ArrayList<>();
        root.changeLog.iterate(all::add);
        all.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    // ==================== 内部:通用向量检索 ====================

    private <E> List<Scored<E>> search(VectorIndex<E> index, float[] query, int topK, double threshold) {
        List<Scored<E>> out = new ArrayList<>();
        if (index == null || query == null || topK <= 0) return out;
        VectorSearchResult<E> res = index.search(query, topK);
        for (ScoredSearchResult.Entry<E> e : res) {
            if (e.score() >= threshold) {
                out.add(new Scored<>(e.entity(), e.score()));
            }
        }
        return out;
    }

    // ==================== 访问器 ====================

    public MemoryRoot root() { return root; }

    public boolean isOpen() { return mgr != null; }

    public String label() { return label; }
}
