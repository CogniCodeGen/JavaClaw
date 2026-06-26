package com.javaclaw.agent.expert;

import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.prompt.AgentPrompts;
import com.javaclaw.config.DataManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.reader.PDFReader;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextReader;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 知识专家子智能体
 *
 * <p>支持双知识库（全局 + 工作区）：
 * <ul>
 *   <li>全局知识库：存储在 workspaces/global/data/knowledge/，所有工作区共享</li>
 *   <li>工作区知识库：存储在 workspaces/{id}/data/knowledge/，仅当前工作区可用</li>
 * </ul>
 * 启动时从两个路径加载，导入时按 scope 分别持久化。</p>
 *
 * @author JavaClaw
 */
public class KnowledgeExpert {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExpert.class);

    /** 持久化文件名 */
    private static final String STORE_FILE = "knowledge-store.json";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 知识库作用域 */
    public enum Scope { GLOBAL, WORKSPACE }

    private final ReActAgent agent;
    private final SubAgentTool tool;

    /** RAG 知识库（启用 RAG 时非 null） */
    private SimpleKnowledge knowledge;

    /** 向量存储 */
    private InMemoryStore store;

    /** 已导入的文档记录（文件名 → 文档条目） */
    private final Map<String, DocEntry> importedDocuments = new ConcurrentHashMap<>();

    /** 文档总数统计 */
    private final AtomicInteger totalDocCount = new AtomicInteger(0);

    /** 文本读取器 */
    private TextReader textReader;

    /** PDF 读取器 */
    private PDFReader pdfReader;

    /** 向量维度 */
    private int dimensions;

    // ==================== 持久化数据结构 ====================

    /**
     * 单个文档条目
     */
    public static class DocEntry {
        public int chunkCount;
        public String importTime;
        public String scope;  // "GLOBAL" 或 "WORKSPACE"
        public List<ChunkData> chunks = new ArrayList<>();

        public DocEntry() {}

        public DocEntry(int chunkCount, String importTime, Scope scope) {
            this.chunkCount = chunkCount;
            this.importTime = importTime;
            this.scope = scope.name();
        }

        public Scope getScope() {
            if (scope == null) return Scope.WORKSPACE;
            try { return Scope.valueOf(scope); } catch (Exception e) { return Scope.WORKSPACE; }
        }
    }

    /**
     * 单个分块的持久化数据
     */
    public static class ChunkData {
        public String docId;
        public String chunkId;
        public String content;
        public double[] embedding;
        public String vectorName;
        public Map<String, Object> payload;

        public ChunkData() {}
    }

    /**
     * 知识库持久化文件的顶层结构
     */
    public static class KnowledgeStoreData {
        public int dimensions;
        public Map<String, DocEntry> documents = new LinkedHashMap<>();

        public KnowledgeStoreData() {}
    }

    // ==================== 构造 ====================

    public KnowledgeExpert(ModelFactory modelFactory) {
        AgentConfig config = AgentConfig.getInstance();
        boolean ragEnabled = config.isRagEnabled();

        ReActAgent builtAgent;
        if (ragEnabled) {
            try {
                builtAgent = buildRagAgent(modelFactory, config);
                // 从全局 + 工作区恢复已持久化的知识库数据
                loadAllStores();
            } catch (Exception e) {
                log.warn("RAG 知识库初始化失败，回退到纯推理模式: {}", e.getMessage());
                builtAgent = buildSimpleAgent(modelFactory);
                this.knowledge = null;
                this.store = null;
                ragEnabled = false;
            }
        } else {
            builtAgent = buildSimpleAgent(modelFactory);
        }
        this.agent = builtAgent;

        log.info("知识专家子智能体已创建: {}, RAG: {}", AgentConfig.KNOWLEDGE_AGENT_NAME,
                ragEnabled ? "已启用" : "未启用");

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

    private ReActAgent buildRagAgent(ModelFactory modelFactory, AgentConfig config) {
        EmbeddingModel embeddingModel = createEmbeddingModel(config);

        this.dimensions = config.getRagEmbeddingDimensions();
        this.store = InMemoryStore.builder()
                .dimensions(dimensions)
                .build();

        this.knowledge = SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build();

        int chunkSize = config.getRagChunkSize();
        int chunkOverlap = config.getRagChunkOverlap();
        this.textReader = new TextReader(chunkSize, SplitStrategy.PARAGRAPH, chunkOverlap);
        this.pdfReader = new PDFReader(chunkSize, SplitStrategy.PARAGRAPH, chunkOverlap);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(this);

        RetrieveConfig retrieveConfig = RetrieveConfig.builder()
                .limit(config.getRagRetrieveLimit())
                .scoreThreshold(config.getRagScoreThreshold())
                .build();

        return ReActAgent.builder()
                .name(AgentConfig.KNOWLEDGE_AGENT_NAME)
                .sysPrompt(AgentPrompts.KNOWLEDGE_AGENT_RAG_SYS_PROMPT)
                .model(modelFactory.createChatModel())
                .toolkit(toolkit)
                .knowledge(knowledge)
                .ragMode(RAGMode.AGENTIC)
                .retrieveConfig(retrieveConfig)
                .maxIters(config.getRagRetrieveLimit() > 0 ? 8 : 1)
                .build();
    }

    private EmbeddingModel createEmbeddingModel(AgentConfig config) {
        String provider = config.getRagEmbeddingProvider();
        String modelName = config.getRagEmbeddingModelName();
        int dimensions = config.getRagEmbeddingDimensions();
        String apiKey = config.getRagEmbeddingApiKey();
        String baseUrl = config.getRagEmbeddingBaseUrl();

        OpenAITextEmbedding.Builder builder = OpenAITextEmbedding.builder()
                .modelName(modelName)
                .dimensions(dimensions);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }

        log.info("{} 嵌入模型已创建（OpenAI 兼容）— model: {}, dimensions: {}, baseUrl: {}",
                provider, modelName, dimensions, baseUrl);
        return builder.build();
    }

    // ==================== 持久化 ====================

    private Path getStoreFile(Scope scope) {
        DataManager dm = DataManager.getInstance();
        Path dir = (scope == Scope.GLOBAL) ? dm.getGlobalKnowledgeDir() : dm.getKnowledgeDir();
        return dir.resolve(STORE_FILE);
    }

    /**
     * 保存指定 scope 的知识库数据
     */
    private void saveStore(Scope scope) {
        try {
            KnowledgeStoreData data = new KnowledgeStoreData();
            data.dimensions = this.dimensions;
            // 只写入对应 scope 的文档
            for (Map.Entry<String, DocEntry> e : importedDocuments.entrySet()) {
                if (e.getValue().getScope() == scope) {
                    data.documents.put(e.getKey(), e.getValue());
                }
            }
            JSON.writeValue(getStoreFile(scope).toFile(), data);
            log.info("知识库已持久化 [{}]，{} 个文档", scope, data.documents.size());
        } catch (IOException e) {
            log.error("持久化知识库失败 [{}]", scope, e);
        }
    }

    /**
     * 保存所有变更的知识库（全局 + 工作区）
     */
    private void saveAllStores() {
        saveStore(Scope.GLOBAL);
        saveStore(Scope.WORKSPACE);
    }

    /**
     * 加载全局 + 工作区知识库数据
     */
    private void loadAllStores() {
        loadStore(Scope.GLOBAL);
        loadStore(Scope.WORKSPACE);
    }

    /**
     * 从指定 scope 加载知识库
     */
    private void loadStore(Scope scope) {
        Path storeFile = getStoreFile(scope);
        if (!Files.exists(storeFile)) {
            log.info("[{}] 无已持久化的知识库数据", scope);
            return;
        }

        try {
            KnowledgeStoreData data = JSON.readValue(storeFile.toFile(), KnowledgeStoreData.class);
            if (data.documents == null || data.documents.isEmpty()) {
                return;
            }

            if (data.dimensions != this.dimensions) {
                log.warn("[{}] 持久化数据维度 ({}) 与当前配置 ({}) 不匹配，跳过加载",
                        scope, data.dimensions, this.dimensions);
                return;
            }

            int restoredChunks = 0;
            int restoredDocs = 0;

            for (Map.Entry<String, DocEntry> entry : data.documents.entrySet()) {
                String sourceName = entry.getKey();
                DocEntry docEntry = entry.getValue();
                // 确保 scope 正确（旧数据可能没有 scope 字段）
                docEntry.scope = scope.name();

                if (docEntry.chunks == null || docEntry.chunks.isEmpty()) {
                    continue;
                }

                List<Document> docs = new ArrayList<>();
                for (ChunkData chunk : docEntry.chunks) {
                    TextBlock contentBlock = TextBlock.builder().text(chunk.content).build();
                    DocumentMetadata metadata = new DocumentMetadata(
                            contentBlock, chunk.docId, chunk.chunkId, chunk.payload);
                    Document doc = new Document(metadata);
                    doc.setEmbedding(chunk.embedding);
                    if (chunk.vectorName != null) {
                        doc.setVectorName(chunk.vectorName);
                    }
                    docs.add(doc);
                }

                store.add(docs).block();
                importedDocuments.put(sourceName, docEntry);
                restoredChunks += docEntry.chunkCount;
                restoredDocs++;
            }

            totalDocCount.addAndGet(restoredChunks);
            log.info("[{}] 已恢复知识库：{} 个文档，{} 个片段", scope, restoredDocs, restoredChunks);
        } catch (Exception e) {
            log.error("[{}] 加载持久化知识库失败", scope, e);
        }
    }

    private List<ChunkData> toChunkDataList(List<Document> docs) {
        List<ChunkData> chunks = new ArrayList<>();
        for (Document doc : docs) {
            ChunkData chunk = new ChunkData();
            chunk.docId = doc.getMetadata().getDocId();
            chunk.chunkId = doc.getMetadata().getChunkId();
            chunk.content = doc.getMetadata().getContentText();
            chunk.embedding = doc.getEmbedding();
            chunk.vectorName = doc.getVectorName();
            chunk.payload = doc.getPayload().isEmpty() ? null : new HashMap<>(doc.getPayload());
            chunks.add(chunk);
        }
        return chunks;
    }

    // ==================== 知识库管理工具 ====================

    /**
     * 导入文件到知识库（供智能体调用，默认导入到工作区知识库）
     */
    @Tool(name = "knowledge_import_file", description = "导入文件到知识库，支持 TXT、MD、PDF 格式。")
    public String knowledge_import_file(
            @ToolParam(name = "filePath", description = "文件的绝对路径") String filePath) {
        return importFile(filePath, Scope.WORKSPACE);
    }

    /**
     * 导入文件到指定 scope 的知识库（供 UI 调用）
     */
    public String importFile(String filePath, Scope scope) {
        if (knowledge == null) {
            return ToolResponse.error("knowledge_import_file", "RAG 知识库未启用，请在设置中开启");
        }
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return ToolResponse.error("knowledge_import_file", "文件不存在: " + filePath);
            }

            String fileName = path.getFileName().toString().toLowerCase();
            List<Document> docs;

            if (fileName.endsWith(".pdf")) {
                docs = pdfReader.read(ReaderInput.fromString(filePath)).block();
            } else {
                String content = Files.readString(path);
                docs = textReader.read(ReaderInput.fromString(content)).block();
            }

            if (docs == null || docs.isEmpty()) {
                return ToolResponse.error("knowledge_import_file", "文件内容为空或无法解析: " + filePath);
            }

            String name = path.getFileName().toString();

            for (Document doc : docs) {
                doc.setVectorName(name);
            }

            knowledge.addDocuments(docs).block();

            DocEntry entry = new DocEntry(docs.size(), LocalDateTime.now().format(TIME_FMT), scope);
            entry.chunks = toChunkDataList(docs);
            importedDocuments.put(name, entry);
            totalDocCount.addAndGet(docs.size());

            saveStore(scope);

            String scopeLabel = scope == Scope.GLOBAL ? "全局" : "工作区";
            return ToolResponse.success("knowledge_import_file",
                    String.format("已导入文件 [%s] 到%s知识库，分割为 %d 个文档片段，总计 %d 个片段",
                            name, scopeLabel, docs.size(), totalDocCount.get()));
        } catch (Exception e) {
            log.error("导入文件到知识库失败: {}", filePath, e);
            return ToolResponse.error("knowledge_import_file", "导入失败: " + e.getMessage());
        }
    }

    /**
     * 导入文本到知识库（供智能体调用，默认导入到工作区知识库）
     */
    @Tool(name = "knowledge_import_text", description = "导入文本内容到知识库。")
    public String knowledge_import_text(
            @ToolParam(name = "text", description = "文本内容") String text,
            @ToolParam(name = "title", description = "文档标题") String title) {
        return importText(text, title, Scope.WORKSPACE);
    }

    /**
     * 导入文本到指定 scope 的知识库（供 UI 调用）
     */
    public String importText(String text, String title, Scope scope) {
        if (knowledge == null) {
            return ToolResponse.error("knowledge_import_text", "RAG 知识库未启用，请在设置中开启");
        }
        try {
            if (text == null || text.isBlank()) {
                return ToolResponse.error("knowledge_import_text", "文本内容不能为空");
            }
            if (title == null || title.isBlank()) {
                title = "手动导入文本";
            }

            List<Document> docs = textReader.read(ReaderInput.fromString(text)).block();
            if (docs == null || docs.isEmpty()) {
                return ToolResponse.error("knowledge_import_text", "文本内容无法解析为文档片段");
            }

            for (Document doc : docs) {
                doc.setVectorName(title);
            }

            knowledge.addDocuments(docs).block();

            DocEntry entry = new DocEntry(docs.size(), LocalDateTime.now().format(TIME_FMT), scope);
            entry.chunks = toChunkDataList(docs);
            importedDocuments.put(title, entry);
            totalDocCount.addAndGet(docs.size());

            saveStore(scope);

            String scopeLabel = scope == Scope.GLOBAL ? "全局" : "工作区";
            return ToolResponse.success("knowledge_import_text",
                    String.format("已导入文本 [%s] 到%s知识库，分割为 %d 个文档片段，总计 %d 个片段",
                            title, scopeLabel, docs.size(), totalDocCount.get()));
        } catch (Exception e) {
            log.error("导入文本到知识库失败", e);
            return ToolResponse.error("knowledge_import_text", "导入失败: " + e.getMessage());
        }
    }

    @Tool(name = "knowledge_list", description = "查看知识库中已导入的文档列表和统计信息")
    public String knowledge_list() {
        if (knowledge == null) {
            return ToolResponse.error("knowledge_list", "RAG 知识库未启用，请在设置中开启");
        }

        if (importedDocuments.isEmpty()) {
            return ToolResponse.success("knowledge_list", "知识库为空，尚未导入任何文档");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("知识库文档列表：\n");
        int index = 1;
        for (Map.Entry<String, DocEntry> entry : importedDocuments.entrySet()) {
            String scopeTag = entry.getValue().getScope() == Scope.GLOBAL ? "[全局]" : "[工作区]";
            sb.append(String.format("%d. %s %s（%d 个片段，导入于 %s）\n",
                    index++, scopeTag, entry.getKey(), entry.getValue().chunkCount,
                    entry.getValue().importTime != null ? entry.getValue().importTime : "未知"));
        }
        sb.append(String.format("\n总计 %d 个文档，%d 个片段", importedDocuments.size(), totalDocCount.get()));

        return ToolResponse.success("knowledge_list", sb.toString());
    }

    @Tool(name = "knowledge_delete", description = "删除知识库中的指定文档。")
    public String knowledge_delete(
            @ToolParam(name = "documentName", description = "文档名称（文件名或导入标题）") String documentName) {
        if (knowledge == null) {
            return ToolResponse.error("knowledge_delete", "RAG 知识库未启用，请在设置中开启");
        }
        if (documentName == null || documentName.isBlank()) {
            return ToolResponse.error("knowledge_delete", "文档名称不能为空");
        }

        DocEntry entry = importedDocuments.remove(documentName);
        if (entry == null) {
            return ToolResponse.error("knowledge_delete", "未找到文档: " + documentName);
        }

        Scope docScope = entry.getScope();

        int deleted = 0;
        if (entry.chunks != null) {
            for (ChunkData chunk : entry.chunks) {
                TextBlock contentBlock = TextBlock.builder().text(chunk.content).build();
                DocumentMetadata metadata = new DocumentMetadata(
                        contentBlock, chunk.docId, chunk.chunkId, chunk.payload);
                Document tempDoc = new Document(metadata);
                Boolean removed = store.delete(tempDoc.getId()).block();
                if (Boolean.TRUE.equals(removed)) {
                    deleted++;
                }
            }
        }

        totalDocCount.addAndGet(-entry.chunkCount);
        if (totalDocCount.get() < 0) {
            totalDocCount.set(0);
        }

        saveStore(docScope);

        return ToolResponse.success("knowledge_delete",
                String.format("已删除文档 [%s]（%d 个片段），知识库剩余 %d 个文档，%d 个片段",
                        documentName, deleted, importedDocuments.size(), totalDocCount.get()));
    }

    @Tool(name = "knowledge_clear", description = "清空知识库中的所有文档")
    public String knowledge_clear() {
        if (knowledge == null) {
            return ToolResponse.error("knowledge_clear", "RAG 知识库未启用，请在设置中开启");
        }

        store.clear();
        int prevCount = totalDocCount.getAndSet(0);
        importedDocuments.clear();

        saveAllStores();

        return ToolResponse.success("knowledge_clear",
                String.format("知识库已清空，共删除 %d 个文档片段", prevCount));
    }

    // ==================== 文本关键词检索（不依赖嵌入 API） ====================

    /**
     * 基于关键词的文本检索 — 当嵌入 API 不可用时的降级方案
     *
     * <p>直接在持久化的 chunk 文本中搜索关键词，按命中关键词数量排序。
     * 不需要调用嵌入 API，始终可用。</p>
     */
    @Tool(name = "knowledge_search",
            description = "基于关键词搜索知识库文档内容。当 retrieve_knowledge 调用失败时使用此工具作为备选。" +
                    "传入多个关键词（空格分隔），返回包含最多关键词的文档片段。")
    public String knowledge_search(
            @ToolParam(name = "keywords", description = "搜索关键词，多个关键词用空格分隔") String keywords) {
        log.debug("工具调用: knowledge_search('{}')", keywords);
        if (importedDocuments.isEmpty()) {
            return ToolResponse.error("knowledge_search", "知识库为空，尚未导入任何文档");
        }
        if (keywords == null || keywords.isBlank()) {
            return ToolResponse.error("knowledge_search", "关键词不能为空");
        }
        String result = textSearch(keywords, null, AgentConfig.getInstance().getRagRetrieveLimit());
        if (result == null) {
            return ToolResponse.error("knowledge_search",
                    "未找到包含关键词的文档片段，请换用更宽泛的关键词重试");
        }
        // textSearch 返回的是上下文注入格式，转换为工具响应格式
        return ToolResponse.success("knowledge_search", result);
    }

    /**
     * 文本关键词检索的核心实现
     *
     * @param keywords     搜索关键词（空格分隔）
     * @param selectedDocs 限定的文档集合（null 或空表示全部文档）
     * @param limit        最大返回数
     * @return 格式化的检索结果，无匹配时返回 null
     */
    private String textSearch(String keywords, Set<String> selectedDocs, int limit) {
        String[] terms = keywords.toLowerCase().split("\\s+");
        boolean filterBySelection = selectedDocs != null && !selectedDocs.isEmpty();

        // 对所有 chunk 计算关键词命中分数
        record ScoredChunk(String source, String content, int hits) {}
        List<ScoredChunk> scored = new ArrayList<>();

        for (Map.Entry<String, DocEntry> entry : importedDocuments.entrySet()) {
            String docName = entry.getKey();
            if (filterBySelection && !selectedDocs.contains(docName)) {
                continue;
            }
            if (entry.getValue().chunks == null) continue;

            for (ChunkData chunk : entry.getValue().chunks) {
                if (chunk.content == null || chunk.content.isBlank()) continue;
                String lower = chunk.content.toLowerCase();
                int hits = 0;
                for (String term : terms) {
                    if (!term.isEmpty() && lower.contains(term)) {
                        hits++;
                    }
                }
                if (hits > 0) {
                    scored.add(new ScoredChunk(docName, chunk.content, hits));
                }
            }
        }

        if (scored.isEmpty()) {
            return null;
        }

        // 按命中数降序排列，取 top-N
        scored.sort((a, b) -> Integer.compare(b.hits(), a.hits()));
        List<ScoredChunk> topN = scored.subList(0, Math.min(limit, scored.size()));

        StringBuilder sb = new StringBuilder();
        sb.append("--- 以下是从知识库中检索到的相关参考资料（请优先参考） ---\n");
        for (int i = 0; i < topN.size(); i++) {
            ScoredChunk sc = topN.get(i);
            sb.append(String.format("\n[参考 %d]（来源: %s，命中 %d 个关键词）\n%s\n",
                    i + 1, sc.source(), sc.hits(), sc.content()));
        }
        sb.append("\n--- 参考资料结束 ---");

        log.info("文本检索到 {} 个相关片段（关键词: {}）", topN.size(), keywords);
        return sb.toString();
    }

    // ==================== 知识库检索（供外部直接调用） ====================

    /**
     * 检索知识库中与查询相关的文档片段
     *
     * <p>优先使用向量语义检索，失败时降级到文本关键词检索。
     * 两种方式都无结果时返回错误提示，让模型告知用户检索失败。
     * 当 selectedDocs 非空时，仅从选中文档中检索；为 null 或空时，从全部文档中检索。</p>
     */
    public String retrieveContext(String query, Set<String> selectedDocs) {
        if (importedDocuments.isEmpty()) {
            return null;
        }
        boolean filterBySelection = selectedDocs != null && !selectedDocs.isEmpty();
        String vectorError = null;

        // 优先尝试向量语义检索
        if (knowledge != null) {
            try {
                String result = vectorRetrieve(query, selectedDocs, filterBySelection);
                if (result != null) {
                    return result;
                }
                log.info("向量检索无结果，降级到文本关键词检索");
            } catch (Exception e) {
                vectorError = e.getMessage();
                log.warn("向量检索失败（{}），降级到文本关键词检索", vectorError);
            }
        }

        // 降级：文本关键词检索
        int limit = AgentConfig.getInstance().getRagRetrieveLimit();
        String textResult = textSearch(query, selectedDocs, limit);
        if (textResult != null) {
            return textResult;
        }

        // 两种检索都无结果 — 返回错误提示注入到上下文，让模型告知用户
        String reason = vectorError != null
                ? "向量语义检索失败（" + vectorError + "），文本关键词检索也无匹配结果"
                : "未检索到与问题相关的知识库内容";
        log.warn("知识库检索完全失败: {}", reason);
        return "\n--- 知识库检索失败：" + reason + "。请告知用户知识库检索异常，并建议检查嵌入模型配置。 ---\n";
    }

    /**
     * 向量语义检索（需要嵌入 API 可用）
     */
    private String vectorRetrieve(String query, Set<String> selectedDocs, boolean filterBySelection) {
        AgentConfig config = AgentConfig.getInstance();
        int retrieveLimit = config.getRagRetrieveLimit();
        int expandedLimit = Math.min(
                filterBySelection ? retrieveLimit * 3 : retrieveLimit,
                totalDocCount.get());
        RetrieveConfig retrieveConfig = RetrieveConfig.builder()
                .limit(Math.max(expandedLimit, retrieveLimit))
                .scoreThreshold(config.getRagScoreThreshold())
                .build();

        List<Document> results = knowledge.retrieve(query, retrieveConfig).block();
        if (results == null || results.isEmpty()) {
            return null;
        }

        // 构建 docId → vectorName 反查映射（绕过 InMemoryStore.search 未复制 vectorName 的问题）
        Map<String, String> docIdToVectorName = new HashMap<>();
        for (Map.Entry<String, DocEntry> entry : importedDocuments.entrySet()) {
            String vectorName = entry.getKey();
            for (ChunkData chunk : entry.getValue().chunks) {
                if (chunk.docId != null) {
                    docIdToVectorName.put(chunk.docId, vectorName);
                }
            }
        }

        // 按选中文档过滤（未选中时跳过过滤，使用全部结果）
        List<Document> finalResults;
        if (filterBySelection) {
            finalResults = results.stream()
                    .filter(doc -> {
                        String vn = doc.getVectorName();
                        if (vn == null) {
                            vn = docIdToVectorName.get(doc.getMetadata().getDocId());
                        }
                        return vn != null && selectedDocs.contains(vn);
                    })
                    .limit(retrieveLimit)
                    .toList();
        } else {
            finalResults = results.stream().limit(retrieveLimit).toList();
        }

        if (finalResults.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("--- 以下是从知识库中检索到的相关参考资料（请优先参考） ---\n");
        for (int i = 0; i < finalResults.size(); i++) {
            Document doc = finalResults.get(i);
            String text = doc.getMetadata().getContentText();
            double score = doc.getScore() != null ? doc.getScore() : 0.0;
            String source = doc.getVectorName();
            if (source == null) {
                source = docIdToVectorName.getOrDefault(doc.getMetadata().getDocId(), "未知来源");
            }
            sb.append(String.format("\n[参考 %d]（来源: %s，相关度: %.2f）\n%s\n",
                    i + 1, source, score, text));
        }
        sb.append("\n--- 参考资料结束 ---");

        log.info("向量检索到 {} 个相关片段（{}）", finalResults.size(),
                filterBySelection ? "按选中文档过滤" : "全部文档");
        return sb.toString();
    }

    public boolean hasDocuments() {
        return !importedDocuments.isEmpty();
    }

    // ==================== 公开访问器 ====================

    public SubAgentTool getTool() {
        return tool;
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public boolean isRagEnabled() {
        return knowledge != null;
    }

    /**
     * 获取所有已导入文档的名称列表
     */
    public List<String> getDocumentNames() {
        return new ArrayList<>(importedDocuments.keySet());
    }

    /**
     * 获取指定 scope 的文档名称列表
     */
    public List<String> getDocumentNames(Scope scope) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, DocEntry> e : importedDocuments.entrySet()) {
            if (e.getValue().getScope() == scope) {
                names.add(e.getKey());
            }
        }
        return names;
    }

    public int getDocumentChunkCount(String name) {
        DocEntry entry = importedDocuments.get(name);
        return entry != null ? entry.chunkCount : 0;
    }

    public String getDocumentImportTime(String name) {
        DocEntry entry = importedDocuments.get(name);
        return entry != null ? entry.importTime : null;
    }

    /**
     * 获取文档的作用域
     */
    public Scope getDocumentScope(String name) {
        DocEntry entry = importedDocuments.get(name);
        return entry != null ? entry.getScope() : Scope.WORKSPACE;
    }

    public int getDocumentCount() {
        return importedDocuments.size();
    }

    public int getTotalChunkCount() {
        return totalDocCount.get();
    }
}
