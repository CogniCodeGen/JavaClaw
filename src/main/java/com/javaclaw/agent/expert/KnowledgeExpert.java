package com.javaclaw.agent.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.DataManager;
import com.javaclaw.memory.embed.EmbeddingGate;
import com.javaclaw.memory.model.KnowledgeChunk;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.prompt.AgentPrompts;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.PDFReader;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextReader;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识专家子智能体（EclipseStore + JVector 重建）
 *
 * <p>双知识库（全局 + 工作区）各由一个 {@link MemoryStore} 承载（EclipseStore 对象图 + JVector 向量索引），
 * 取代旧 AgentScope {@code InMemoryStore}/{@code SimpleKnowledge} + 手写 {@code knowledge-store.json}。
 * 来源文档名随 {@link KnowledgeChunk} 实体持久化 —— 彻底消除旧 InMemoryStore 不复制 vectorName 的绕路。</p>
 *
 * <p>存储目录：全局 {@code global/data/knowledge/store}、工作区 {@code {ws}/data/knowledge/store}；
 * 首次打开时一次性迁移同目录旧 {@code knowledge-store.json}（迁移后改名为 .migrated 留痕）。</p>
 *
 * @author JavaClaw
 */
public class KnowledgeExpert {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExpert.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 知识库作用域 */
    public enum Scope { GLOBAL, WORKSPACE }

    private final ReActAgent agent;
    private final SubAgentTool tool;

    private final boolean ragEnabled;
    private EmbeddingGate gate;
    private MemoryStore globalStore;
    private MemoryStore workspaceStore;
    private TextReader textReader;
    private PDFReader pdfReader;

    /**
     * 「不参与检索」的文档名集合（持久化到工作区 {@code data/knowledge-doc-prefs.json}）。
     * 检索默认启用全部已导入文档（设计稿语义：默认全部启用、可在知识库中心逐篇停用）；
     * 故此处只记录被显式停用的文档名 —— 新导入文档自动启用，无需写入。
     * 全局文档名也记于此（按工作区维度决定是否参与本工作区会话检索）。
     */
    private final Set<String> disabledDocs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 检索命中片段（供知识库中心「检索测试」结构化展示）。 */
    public record KnowledgeHit(String docName, String scope, double score, String content) {}

    // ==================== 构造 ====================

    public KnowledgeExpert(ModelFactory modelFactory) {
        AgentConfig config = AgentConfig.getInstance();
        boolean enabled = config.isRagEnabled();
        ReActAgent builtAgent;

        if (enabled) {
            try {
                this.gate = new EmbeddingGate(modelFactory);
                int dim = gate.dimensions();
                this.globalStore = new MemoryStore(
                        DataManager.getInstance().getGlobalKnowledgeDir().resolve("store"), dim, "knowledge-global");
                this.workspaceStore = new MemoryStore(
                        DataManager.getInstance().getKnowledgeDir().resolve("store"), dim, "knowledge-workspace");
                this.globalStore.open();
                this.workspaceStore.open();

                int chunkSize = config.getRagChunkSize();
                int chunkOverlap = config.getRagChunkOverlap();
                this.textReader = new TextReader(chunkSize, SplitStrategy.PARAGRAPH, chunkOverlap);
                this.pdfReader = new PDFReader(chunkSize, SplitStrategy.PARAGRAPH, chunkOverlap);

                migrateLegacyJson(Scope.GLOBAL, DataManager.getInstance().getGlobalKnowledgeDir());
                migrateLegacyJson(Scope.WORKSPACE, DataManager.getInstance().getKnowledgeDir());

                loadDocPrefs();

                builtAgent = buildRagAgent(modelFactory);
            } catch (Exception e) {
                log.warn("RAG 知识库初始化失败，回退到纯推理模式: {}", e.getMessage());
                closeStores();
                this.gate = null;
                builtAgent = buildSimpleAgent(modelFactory);
                enabled = false;
            }
        } else {
            builtAgent = buildSimpleAgent(modelFactory);
        }
        this.ragEnabled = enabled;
        this.agent = builtAgent;

        log.info("知识专家子智能体已创建: {}, RAG: {}", AgentConfig.KNOWLEDGE_AGENT_NAME,
                enabled ? "已启用(EclipseStore+JVector)" : "未启用");

        SubAgentConfig subConfig = SubAgentConfig.builder()
                .toolName("knowledge_expert")
                .description(AgentConfig.KNOWLEDGE_AGENT_DESCRIPTION)
                .forwardEvents(true)
                .build();
        this.tool = new SubAgentTool(() -> agent, subConfig);
        log.info("已注册子智能体工具: knowledge_expert");
    }

    private ReActAgent buildSimpleAgent(ModelFactory modelFactory) {
        return ReActAgent.builder()
                .name(AgentConfig.KNOWLEDGE_AGENT_NAME)
                .sysPrompt(AgentPrompts.KNOWLEDGE_AGENT_SYS_PROMPT)
                .model(modelFactory.createChatModel())
                .maxIters(1)
                .build();
    }

    private ReActAgent buildRagAgent(ModelFactory modelFactory) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(this);
        return ReActAgent.builder()
                .name(AgentConfig.KNOWLEDGE_AGENT_NAME)
                .sysPrompt(AgentPrompts.KNOWLEDGE_AGENT_RAG_SYS_PROMPT)
                .model(modelFactory.createChatModel())
                .toolkit(toolkit)
                .maxIters(8)
                .build();
    }

    private MemoryStore storeOf(Scope scope) {
        return scope == Scope.GLOBAL ? globalStore : workspaceStore;
    }

    private void closeStores() {
        if (globalStore != null) { globalStore.close(); globalStore = null; }
        if (workspaceStore != null) { workspaceStore.close(); workspaceStore = null; }
    }

    // ==================== 旧 JSON 一次性迁移 ====================

    private void migrateLegacyJson(Scope scope, Path knowledgeDir) {
        Path json = knowledgeDir.resolve("knowledge-store.json");
        if (!Files.exists(json)) return;
        try {
            MigStore data = JSON.readValue(json.toFile(), MigStore.class);
            int migrated = 0;
            if (data != null && data.documents != null) {
                for (Map.Entry<String, MigDoc> de : data.documents.entrySet()) {
                    String docName = de.getKey();
                    MigDoc doc = de.getValue();
                    if (doc.chunks == null) continue;
                    for (MigChunk ch : doc.chunks) {
                        if (ch.content == null || ch.embedding == null) continue;
                        KnowledgeChunk kc = new KnowledgeChunk(docName, scope.name(), ch.content, toFloat(ch.embedding));
                        kc.importTime = doc.importTime;
                        storeOf(scope).addKnowledgeChunk(kc, "migration");
                        migrated++;
                    }
                }
            }
            Files.move(json, knowledgeDir.resolve("knowledge-store.json.migrated"));
            log.info("[{}] 已迁移旧知识库 JSON：{} 个分块 → EclipseStore", scope, migrated);
        } catch (Exception e) {
            log.warn("[{}] 旧知识库 JSON 迁移失败（跳过，不影响新库）: {}", scope, e.getMessage());
        }
    }

    private static float[] toFloat(double[] d) {
        float[] f = new float[d.length];
        for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
        return f;
    }

    // ==================== 导入工具 ====================

    @Tool(name = "knowledge_import_file", description = "导入文件到知识库，支持 TXT、MD、PDF 格式。")
    public String knowledge_import_file(
            @ToolParam(name = "filePath", description = "文件的绝对路径") String filePath) {
        return importFile(filePath, Scope.WORKSPACE);
    }

    public String importFile(String filePath, Scope scope) {
        if (!ragEnabled) {
            return ToolResponse.error("knowledge_import_file", "RAG 知识库未启用，请在设置中开启");
        }
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return ToolResponse.error("knowledge_import_file", "文件不存在: " + filePath);
            }
            String name = path.getFileName().toString();
            String lower = name.toLowerCase();
            List<Document> docs;
            if (lower.endsWith(".pdf")) {
                docs = pdfReader.read(ReaderInput.fromString(filePath)).block();
            } else if (isTextLike(lower)) {
                // 纯文本与 Markdown：按文本读取并分块。Markdown 保留原文结构（标题/列表/代码块），
                // 对检索友好，无需先转纯文本。读取失败（如二进制/编码错误）由外层 catch 返回明确原因。
                docs = textReader.read(ReaderInput.fromString(Files.readString(path))).block();
            } else {
                return ToolResponse.error("knowledge_import_file",
                        "不支持的文件类型: " + name + "（目前支持 PDF 与 TXT / Markdown 等文本文件）");
            }
            if (docs == null || docs.isEmpty()) {
                return ToolResponse.error("knowledge_import_file", "文件内容为空或无法解析: " + filePath);
            }
            int added = storeChunks(name, scope, docs);
            String scopeLabel = scope == Scope.GLOBAL ? "全局" : "工作区";
            if (added == 0) {
                return ToolResponse.error("knowledge_import_file", embedFailureDetail());
            }
            return ToolResponse.success("knowledge_import_file",
                    String.format("已导入文件 [%s] 到%s知识库，写入 %d 个分块，总计 %d 个分块",
                            name, scopeLabel, added, getTotalChunkCount()));
        } catch (Exception e) {
            log.error("导入文件到知识库失败: {}", filePath, e);
            return ToolResponse.error("knowledge_import_file", "导入失败: " + e.getMessage());
        }
    }

    @Tool(name = "knowledge_import_text", description = "导入文本内容到知识库。")
    public String knowledge_import_text(
            @ToolParam(name = "text", description = "文本内容") String text,
            @ToolParam(name = "title", description = "文档标题") String title) {
        return importText(text, title, Scope.WORKSPACE);
    }

    public String importText(String text, String title, Scope scope) {
        if (!ragEnabled) {
            return ToolResponse.error("knowledge_import_text", "RAG 知识库未启用，请在设置中开启");
        }
        try {
            if (text == null || text.isBlank()) {
                return ToolResponse.error("knowledge_import_text", "文本内容不能为空");
            }
            String docName = (title == null || title.isBlank()) ? "手动导入文本" : title;
            List<Document> docs = textReader.read(ReaderInput.fromString(text)).block();
            if (docs == null || docs.isEmpty()) {
                return ToolResponse.error("knowledge_import_text", "文本内容无法解析为文档分块");
            }
            int added = storeChunks(docName, scope, docs);
            String scopeLabel = scope == Scope.GLOBAL ? "全局" : "工作区";
            if (added == 0) {
                return ToolResponse.error("knowledge_import_text", embedFailureDetail());
            }
            return ToolResponse.success("knowledge_import_text",
                    String.format("已导入文本 [%s] 到%s知识库，写入 %d 个分块，总计 %d 个分块",
                            docName, scopeLabel, added, getTotalChunkCount()));
        } catch (Exception e) {
            log.error("导入文本到知识库失败", e);
            return ToolResponse.error("knowledge_import_text", "导入失败: " + e.getMessage());
        }
    }

    /** 受支持的文本类文件扩展名（含 Markdown）；其余非 PDF 类型视为不支持。 */
    private boolean isTextLike(String lowerName) {
        return lowerName.endsWith(".txt") || lowerName.endsWith(".text")
                || lowerName.endsWith(".md") || lowerName.endsWith(".markdown")
                || lowerName.endsWith(".log") || lowerName.endsWith(".csv")
                || lowerName.endsWith(".json") || lowerName.endsWith(".xml")
                || lowerName.endsWith(".html") || lowerName.endsWith(".htm");
    }

    /** 把分块嵌入后写入指定 scope 的库，返回成功写入数（嵌入失败的分块跳过）。 */
    private int storeChunks(String docName, Scope scope, List<Document> docs) {
        String now = LocalDateTime.now().format(TIME_FMT);
        MemoryStore store = storeOf(scope);
        int expectedDim = gate.dimensions();
        int added = 0, idx = 0;
        for (Document doc : docs) {
            String content = doc.getMetadata().getContentText();
            if (content == null || content.isBlank()) continue;
            float[] vec = gate.embed(content);
            if (vec == null) continue; // 无嵌入 → 跳过（不写无向量分块）
            // 维度不匹配：配置的 rag.embedding.dimensions 与模型实际输出不一致，
            // 直接抛出明确原因（JVector 索引按 expectedDim 建库，写入会失败/语义错乱）。
            if (vec.length != expectedDim) {
                throw new IllegalStateException(String.format(
                        "嵌入维度不匹配：模型实际返回 %d 维，但 rag.embedding.dimensions 配置为 %d。"
                                + "请把维度改为模型实际值，并清空 data/knowledge/store 与 data/memory-store 后重建索引。",
                        vec.length, expectedDim));
            }
            KnowledgeChunk kc = new KnowledgeChunk(docName, scope.name(), content, vec);
            kc.chunkIndex = idx++;
            kc.importTime = now;
            store.addKnowledgeChunk(kc, "user");
            added++;
        }
        return added;
    }

    /**
     * 构造"未能写入任何分块"时的明确失败原因：优先带上嵌入端点的真实报错
     * （鉴权 / 端点 / 网络等），帮助用户定位为何配置了向量模型仍导入失败。
     */
    private String embedFailureDetail() {
        String why = gate == null ? null : gate.lastError();
        if (why == null || why.isBlank()) {
            return "嵌入不可用，未能写入任何分块（请检查 rag.embedding.* 配置：模型名 / baseUrl / API Key / 维度）";
        }
        return "嵌入调用失败：" + why
                + "（请确认 rag.embedding.* 配置：baseUrl 指向 embeddings 端点、API Key 有效、模型名为嵌入模型）";
    }

    // ==================== 列表 / 删除 / 清空 ====================

    @Tool(name = "knowledge_list", description = "查看知识库中已导入的文档列表和统计信息")
    public String knowledge_list() {
        if (!ragEnabled) {
            return ToolResponse.error("knowledge_list", "RAG 知识库未启用，请在设置中开启");
        }
        Map<String, int[]> counts = new LinkedHashMap<>(); // docName -> [count]
        Map<String, String> scopes = new LinkedHashMap<>();
        Map<String, String> times = new LinkedHashMap<>();
        collectDocMeta(counts, scopes, times);
        if (counts.isEmpty()) {
            return ToolResponse.success("knowledge_list", "知识库为空，尚未导入任何文档");
        }
        StringBuilder sb = new StringBuilder("知识库文档列表：\n");
        int i = 1, total = 0;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            String tag = "GLOBAL".equals(scopes.get(e.getKey())) ? "[全局]" : "[工作区]";
            sb.append(String.format("%d. %s %s（%d 个分块，导入于 %s）\n",
                    i++, tag, e.getKey(), e.getValue()[0],
                    times.getOrDefault(e.getKey(), "未知")));
            total += e.getValue()[0];
        }
        sb.append(String.format("\n总计 %d 个文档，%d 个分块", counts.size(), total));
        return ToolResponse.success("knowledge_list", sb.toString());
    }

    @Tool(name = "knowledge_delete", description = "删除知识库中的指定文档。")
    public String knowledge_delete(
            @ToolParam(name = "documentName", description = "文档名称（文件名或导入标题）") String documentName) {
        if (!ragEnabled) {
            return ToolResponse.error("knowledge_delete", "RAG 知识库未启用，请在设置中开启");
        }
        if (documentName == null || documentName.isBlank()) {
            return ToolResponse.error("knowledge_delete", "文档名称不能为空");
        }
        int removed = globalStore.removeKnowledgeByDoc(documentName, "user")
                + workspaceStore.removeKnowledgeByDoc(documentName, "user");
        if (removed == 0) {
            return ToolResponse.error("knowledge_delete", "未找到文档: " + documentName);
        }
        return ToolResponse.success("knowledge_delete",
                String.format("已删除文档 [%s]（%d 个分块），知识库剩余 %d 个分块",
                        documentName, removed, getTotalChunkCount()));
    }

    @Tool(name = "knowledge_clear", description = "清空知识库中的所有文档")
    public String knowledge_clear() {
        if (!ragEnabled) {
            return ToolResponse.error("knowledge_clear", "RAG 知识库未启用，请在设置中开启");
        }
        int prev = getTotalChunkCount();
        globalStore.clearKnowledge("user");
        workspaceStore.clearKnowledge("user");
        return ToolResponse.success("knowledge_clear",
                String.format("知识库已清空，共删除 %d 个文档分块", prev));
    }

    // ==================== 检索 ====================

    @Tool(name = "knowledge_search",
            description = "基于关键词搜索知识库文档内容。当向量检索不可用时作为备选。传入多个关键词（空格分隔）。")
    public String knowledge_search(
            @ToolParam(name = "keywords", description = "搜索关键词，多个关键词用空格分隔") String keywords) {
        if (!ragEnabled) {
            return ToolResponse.error("knowledge_search", "RAG 知识库未启用，请在设置中开启");
        }
        if (keywords == null || keywords.isBlank()) {
            return ToolResponse.error("knowledge_search", "关键词不能为空");
        }
        String result = textSearch(keywords, null, AgentConfig.getInstance().getRagRetrieveLimit());
        if (result == null) {
            return ToolResponse.error("knowledge_search", "未找到包含关键词的文档分块，请换用更宽泛的关键词重试");
        }
        return ToolResponse.success("knowledge_search", result);
    }

    /**
     * 检索与查询相关的分块（向量优先，失败/无果降级关键词）。selectedDocs 非空时仅从选中文档检索。
     * 供外部直接注入上下文使用；无结果返回 null。
     */
    public String retrieveContext(String query, Set<String> selectedDocs) {
        if (!ragEnabled || getTotalChunkCount() == 0) {
            return null;
        }
        AgentConfig cfg = AgentConfig.getInstance();
        int limit = cfg.getRagRetrieveLimit();
        double threshold = cfg.getRagScoreThreshold();
        boolean filter = selectedDocs != null && !selectedDocs.isEmpty();

        float[] q = gate.embed(query);
        if (q != null) {
            List<MemoryStore.Scored<KnowledgeChunk>> hits = new ArrayList<>();
            hits.addAll(globalStore.searchKnowledge(q, filter ? limit * 3 : limit, threshold));
            hits.addAll(workspaceStore.searchKnowledge(q, filter ? limit * 3 : limit, threshold));
            hits.sort((a, b) -> Float.compare(b.score(), a.score()));
            List<MemoryStore.Scored<KnowledgeChunk>> picked = new ArrayList<>();
            for (MemoryStore.Scored<KnowledgeChunk> h : hits) {
                if (filter && !selectedDocs.contains(h.entity().docName)) continue;
                picked.add(h);
                if (picked.size() >= limit) break;
            }
            if (!picked.isEmpty()) {
                StringBuilder sb = new StringBuilder("--- 以下是从知识库中检索到的相关参考资料（请优先参考） ---\n");
                int i = 1;
                for (MemoryStore.Scored<KnowledgeChunk> h : picked) {
                    sb.append(String.format("\n[参考 %d]（来源: %s，相关度: %.2f）\n%s\n",
                            i++, h.entity().docName, h.score(), h.entity().content));
                }
                sb.append("\n--- 参考资料结束 ---");
                return sb.toString();
            }
        }
        // 降级：关键词
        String text = textSearch(query, selectedDocs, limit);
        if (text != null) return text;
        return "\n--- 知识库检索失败：未检索到相关内容。请告知用户并建议检查嵌入模型配置。 ---\n";
    }

    /** 关键词检索（不依赖嵌入），按命中关键词数排序取 top-N。 */
    private String textSearch(String keywords, Set<String> selectedDocs, int limit) {
        String[] terms = keywords.toLowerCase().split("\\s+");
        boolean filter = selectedDocs != null && !selectedDocs.isEmpty();
        record SC(String src, String content, int hits) {}
        List<SC> scored = new ArrayList<>();
        for (KnowledgeChunk c : allChunks()) {
            if (filter && !selectedDocs.contains(c.docName)) continue;
            if (c.content == null || c.content.isBlank()) continue;
            String lower = c.content.toLowerCase();
            int hits = 0;
            for (String t : terms) if (!t.isEmpty() && lower.contains(t)) hits++;
            if (hits > 0) scored.add(new SC(c.docName, c.content, hits));
        }
        if (scored.isEmpty()) return null;
        scored.sort((a, b) -> Integer.compare(b.hits(), a.hits()));
        List<SC> top = scored.subList(0, Math.min(limit, scored.size()));
        StringBuilder sb = new StringBuilder("--- 以下是从知识库中检索到的相关参考资料（请优先参考） ---\n");
        for (int i = 0; i < top.size(); i++) {
            sb.append(String.format("\n[参考 %d]（来源: %s，命中 %d 个关键词）\n%s\n",
                    i + 1, top.get(i).src(), top.get(i).hits(), top.get(i).content()));
        }
        sb.append("\n--- 参考资料结束 ---");
        return sb.toString();
    }

    // ==================== 公开访问器（供 UI / 其它模块） ====================

    /** 关闭知识库存储（释放 EclipseStore 目录锁与写线程）；工作区切换/应用退出时由 AgentRuntime 调用。 */
    public void close() {
        closeStores();
    }

    public SubAgentTool getTool() { return tool; }

    public boolean isRagEnabled() { return ragEnabled; }

    public boolean hasDocuments() { return getTotalChunkCount() > 0; }

    public int getTotalChunkCount() {
        if (!ragEnabled) return 0;
        return globalStore.allKnowledge().size() + workspaceStore.allKnowledge().size();
    }

    public List<String> getDocumentNames() {
        Set<String> names = new LinkedHashSet<>();
        for (KnowledgeChunk c : allChunks()) names.add(c.docName);
        return new ArrayList<>(names);
    }

    public List<String> getDocumentNames(Scope scope) {
        if (!ragEnabled) return List.of();
        Set<String> names = new LinkedHashSet<>();
        for (KnowledgeChunk c : storeOf(scope).allKnowledge()) names.add(c.docName);
        return new ArrayList<>(names);
    }

    public int getDocumentChunkCount(String name) {
        int n = 0;
        for (KnowledgeChunk c : allChunks()) if (c.docName.equals(name)) n++;
        return n;
    }

    public String getDocumentImportTime(String name) {
        for (KnowledgeChunk c : allChunks()) if (c.docName.equals(name)) return c.importTime;
        return null;
    }

    public Scope getDocumentScope(String name) {
        if (ragEnabled) {
            for (KnowledgeChunk c : globalStore.allKnowledge()) {
                if (c.docName.equals(name)) return Scope.GLOBAL;
            }
        }
        return Scope.WORKSPACE;
    }

    public int getDocumentCount() {
        return getDocumentNames().size();
    }

    // ==================== 供 UI（记忆中心知识库页签）读取 ====================

    /** 返回全局 + 工作区两个知识库的全部分块（供 UI 按文档聚合展示）。 */
    public List<KnowledgeChunk> allKnowledgeChunks() {
        return allChunks();
    }

    /**
     * 重建某文档的向量索引：对其全部分块重新嵌入并回填向量（嵌入不可用则跳过该块）。
     * 跨全局 + 工作区两库处理，返回成功重嵌入的分块数。建议后台线程调用。
     */
    public int reindexDocument(String docName) {
        if (!ragEnabled || docName == null) return 0;
        int n = 0;
        for (MemoryStore store : new MemoryStore[]{globalStore, workspaceStore}) {
            for (KnowledgeChunk c : store.allKnowledge()) {
                if (!docName.equals(c.docName)) continue;
                float[] vec = gate.embed(c.content);
                if (vec != null) {
                    store.updateKnowledgeChunk(c, x -> x.embedding = vec, "user");
                    n++;
                }
            }
        }
        return n;
    }

    // ==================== 检索启用状态（按文档，持久化） ====================

    /** 该文档是否参与对话检索（默认启用；仅显式停用的文档返回 false）。 */
    public boolean isDocEnabled(String docName) {
        return docName != null && !disabledDocs.contains(docName);
    }

    /** 设置某文档是否参与检索，并持久化。 */
    public void setDocEnabled(String docName, boolean enabled) {
        if (docName == null) return;
        boolean changed = enabled ? disabledDocs.remove(docName) : disabledDocs.add(docName);
        if (changed) saveDocPrefs();
    }

    /**
     * 批量设置文档检索启用状态：scope 为 null 时作用于全部文档，否则仅该作用域文档。
     */
    public void setAllEnabled(boolean enabled, Scope scope) {
        List<String> names = scope == null ? getDocumentNames() : getDocumentNames(scope);
        boolean changed = false;
        for (String name : names) {
            changed |= enabled ? disabledDocs.remove(name) : disabledDocs.add(name);
        }
        if (changed) saveDocPrefs();
    }

    /** 当前参与检索的文档名集合（全部已导入文档去掉被停用的）。 */
    public Set<String> getEnabledDocs() {
        Set<String> enabled = new LinkedHashSet<>(getDocumentNames());
        enabled.removeAll(disabledDocs);
        return enabled;
    }

    /** 参与检索的文档篇数（启用计数）。 */
    public int getEnabledDocCount() {
        return getEnabledDocs().size();
    }

    // ==================== 检索测试（结构化结果，供知识库中心） ====================

    /**
     * 检索测试：返回与查询最相关的若干片段（向量优先，失败降级关键词），仅命中已启用文档。
     * 不进入对话，仅用于在知识库中心验证召回质量。
     */
    public List<KnowledgeHit> searchTest(String query, int topK) {
        List<KnowledgeHit> out = new ArrayList<>();
        if (!ragEnabled || query == null || query.isBlank() || getTotalChunkCount() == 0) return out;
        Set<String> enabled = getEnabledDocs();
        if (enabled.isEmpty()) return out;
        double threshold = AgentConfig.getInstance().getRagScoreThreshold();

        float[] q = gate == null ? null : gate.embed(query);
        if (q != null) {
            List<MemoryStore.Scored<KnowledgeChunk>> hits = new ArrayList<>();
            hits.addAll(globalStore.searchKnowledge(q, topK * 3, threshold));
            hits.addAll(workspaceStore.searchKnowledge(q, topK * 3, threshold));
            hits.sort((a, b) -> Float.compare(b.score(), a.score()));
            for (MemoryStore.Scored<KnowledgeChunk> h : hits) {
                KnowledgeChunk c = h.entity();
                if (!enabled.contains(c.docName)) continue;
                out.add(new KnowledgeHit(c.docName, c.scope, h.score(), c.content));
                if (out.size() >= topK) break;
            }
            if (!out.isEmpty()) return out;
        }
        // 降级：关键词命中数排序
        String[] terms = query.toLowerCase().split("\\s+");
        record SC(KnowledgeChunk c, int hits) {}
        List<SC> scored = new ArrayList<>();
        for (KnowledgeChunk c : allChunks()) {
            if (!enabled.contains(c.docName) || c.content == null || c.content.isBlank()) continue;
            String lower = c.content.toLowerCase();
            int n = 0;
            for (String t : terms) if (!t.isEmpty() && lower.contains(t)) n++;
            if (n > 0) scored.add(new SC(c, n));
        }
        scored.sort((a, b) -> Integer.compare(b.hits(), a.hits()));
        for (SC s : scored.subList(0, Math.min(topK, scored.size()))) {
            // 关键词降级无相似度分数，用命中比例粗略归一供进度条展示
            out.add(new KnowledgeHit(s.c().docName, s.c().scope,
                    Math.min(1.0, s.hits() / (double) Math.max(1, terms.length)), s.c().content));
        }
        return out;
    }

    /** 某文档前 max 个片段的正文（按 chunkIndex 排序），供详情抽屉「片段预览」。 */
    public List<String> getDocumentChunkPreviews(String docName, int max) {
        if (!ragEnabled || docName == null) return List.of();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (KnowledgeChunk c : allChunks()) if (docName.equals(c.docName)) chunks.add(c);
        chunks.sort((a, b) -> Integer.compare(a.chunkIndex, b.chunkIndex));
        List<String> out = new ArrayList<>();
        for (KnowledgeChunk c : chunks) {
            if (c.content != null && !c.content.isBlank()) out.add(c.content);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** 重建全部文档的向量索引（对所有分块重新嵌入回填）；建议后台线程调用，返回成功重嵌入的分块数。 */
    public int reindexAll() {
        if (!ragEnabled) return 0;
        int n = 0;
        for (MemoryStore store : new MemoryStore[]{globalStore, workspaceStore}) {
            for (KnowledgeChunk c : store.allKnowledge()) {
                float[] vec = gate.embed(c.content);
                if (vec != null) {
                    store.updateKnowledgeChunk(c, x -> x.embedding = vec, "user");
                    n++;
                }
            }
        }
        return n;
    }

    // ==================== 文档检索启用状态持久化 ====================

    private Path docPrefsFile() {
        return DataManager.getInstance().getDataRoot().resolve("knowledge-doc-prefs.json");
    }

    private void loadDocPrefs() {
        Path f = docPrefsFile();
        if (!Files.exists(f)) return;
        try {
            DocPrefs prefs = JSON.readValue(f.toFile(), DocPrefs.class);
            disabledDocs.clear();
            if (prefs != null && prefs.disabled != null) disabledDocs.addAll(prefs.disabled);
        } catch (Exception e) {
            log.warn("读取知识库文档检索偏好失败（忽略，按默认全部启用）: {}", e.getMessage());
        }
    }

    private void saveDocPrefs() {
        try {
            DocPrefs prefs = new DocPrefs();
            prefs.disabled = new ArrayList<>(disabledDocs);
            JSON.writerWithDefaultPrettyPrinter().writeValue(docPrefsFile().toFile(), prefs);
        } catch (Exception e) {
            log.warn("保存知识库文档检索偏好失败: {}", e.getMessage());
        }
    }

    static class DocPrefs { public List<String> disabled; }

    // ==================== 内部辅助 ====================

    private List<KnowledgeChunk> allChunks() {
        if (!ragEnabled) return List.of();
        List<KnowledgeChunk> all = new ArrayList<>(globalStore.allKnowledge());
        all.addAll(workspaceStore.allKnowledge());
        return all;
    }

    private void collectDocMeta(Map<String, int[]> counts, Map<String, String> scopes, Map<String, String> times) {
        if (!ragEnabled) return;
        accumulate(globalStore.allKnowledge(), counts, scopes, times);
        accumulate(workspaceStore.allKnowledge(), counts, scopes, times);
    }

    private void accumulate(List<KnowledgeChunk> chunks, Map<String, int[]> counts,
                            Map<String, String> scopes, Map<String, String> times) {
        for (KnowledgeChunk c : chunks) {
            counts.computeIfAbsent(c.docName, k -> new int[1])[0]++;
            scopes.putIfAbsent(c.docName, c.scope);
            if (c.importTime != null) times.putIfAbsent(c.docName, c.importTime);
        }
    }

    // ==================== 旧 JSON 迁移 DTO ====================

    static class MigStore { public Map<String, MigDoc> documents; }
    static class MigDoc { public String importTime; public List<MigChunk> chunks; }
    static class MigChunk { public String content; public double[] embedding; }
}
