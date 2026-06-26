package com.javaclaw.agent;

import com.javaclaw.agent.expert.ExpertManager;
import com.javaclaw.agent.expert.KnowledgeExpert;
import com.javaclaw.agent.memory.MemoryManager;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.agent.vision.VisionPreprocessor;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.chat.ChatMessage;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.mcp.McpClientManager;
import com.javaclaw.mcp.McpConfigManager;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 共享基础设施容器
 *
 * <p>三条路径（普通聊天 / 规划模式 / 托管任务）共用的基础组件集中在此类：
 * <ul>
 *   <li>模型工厂 {@link ModelFactory}（共享 HttpTransport）</li>
 *   <li>Token 用量追踪 {@link TokenTracker}</li>
 *   <li>统一记忆管理 {@link MemoryManager}</li>
 *   <li>专家库 {@link ExpertManager}</li>
 *   <li>知识专家 {@link KnowledgeExpert}（RAG）</li>
 *   <li>MCP 客户端管理 {@link McpClientManager}</li>
 *   <li>视觉预处理 {@link VisionPreprocessor}</li>
 *   <li>浏览器管理 {@link PlaywrightBrowserManager}（App 层创建并注入）</li>
 * </ul>
 *
 * <p>此外提供三条路径共用的工具方法：
 * <ul>
 *   <li>{@link #buildUserMsg(String, List)}：用户输入 + 附件 → 多模态 Msg</li>
 *   <li>{@link #enrichWithKnowledge(String)}：RAG 上下文注入</li>
 *   <li>{@link #extractErrorMessage(Throwable)}：从异常链提炼人类可读错误文案</li>
 * </ul>
 *
 * <p>Runtime 本身不承载任何模式的业务逻辑（聊天/规划/任务），只负责持有、
 * 重建、关闭基础设施。三条路径对应的门面服务通过构造参数获取本实例。</p>
 *
 * @author JavaClaw
 */
public final class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    /** 文本文档最大读取字符数 */
    private static final int MAX_TEXT_DOC_CHARS = 20000;

    /** PDF 最大提取字符数 */
    private static final int MAX_PDF_CHARS = 30000;

    // ==================== 基础组件 ====================

    /** 模型工厂（共享 HTTP 传输层） */
    private final ModelFactory modelFactory;

    /** Token 用量追踪器 */
    private final TokenTracker tokenTracker;

    /** 统一记忆管理器 */
    private final MemoryManager memoryManager;

    /** 专家库（子智能体定义 + 工具注册入口） */
    private final ExpertManager expertManager;

    /** 知识专家（含 RAG 能力，多模式共用） */
    private final KnowledgeExpert knowledgeExpert;

    /** MCP 客户端管理器（启动时自动连接启用的 MCP Server） */
    private final McpClientManager mcpClientManager;

    /** 视觉预处理器（把图片附件一次性转文本描述） */
    private final VisionPreprocessor visionPreprocessor;

    /** 浏览器管理器（由 App 层创建并注入，供 WebExpert 使用） */
    private final PlaywrightBrowserManager browserManager;

    /** 能力 → 工具实例映射（供托管任务按能力组装工具） */
    private final Map<String, Object> capabilityTools;

    /** 模型执行重试配置（超时 / 最大重试次数 / 指数退避），三模式共用 */
    private final ExecutionConfig modelExecConfig;

    /** 用户在知识库菜单中勾选的文档名（影响 RAG 注入；空集表示不注入） */
    private volatile Set<String> selectedKnowledgeDocs = Set.of();

    /**
     * 构造并初始化全部共享基础设施。
     *
     * @param browserManager 浏览器管理器（由 App 层创建并注入）
     */
    public AgentRuntime(PlaywrightBrowserManager browserManager) {
        AgentConfig config = AgentConfig.getInstance();
        log.info("========== 初始化 AgentRuntime 基础设施 ==========");
        log.info("API 地址: {}", config.getBaseUrl());
        log.info("模型名称: {}", config.getModelName());

        this.browserManager = browserManager;

        // 1. ModelFactory：共享 HttpTransport，所有模型实例共用
        this.modelFactory = new ModelFactory();

        // 2. TokenTracker：按会话/日期统计 token 用量
        this.tokenTracker = new TokenTracker();
        // 传输层用量观测接入：截获各提供商返回的缓存命中 token（cached_tokens），
        // 让账本能区分全价输入与缓存折扣输入（命中率 = cachedInput / meteredInput）
        com.javaclaw.agent.model.UsageMeteredTransport.setSink(tokenTracker::recordCachedObservation);

        // 3. MemoryManager：统一管理所有智能体的 AutoContextMemory
        //    注意依赖 modelFactory，因此必须在其之后创建
        this.memoryManager = new MemoryManager(modelFactory);

        // 4. ExpertManager + KnowledgeExpert：子智能体定义的中心
        this.expertManager = new ExpertManager(modelFactory, browserManager);
        this.knowledgeExpert = new KnowledgeExpert(modelFactory);

        // 5. 能力 → 工具映射（从 ExpertManager 暴露，TaskExecutor 按能力选择）
        this.capabilityTools = expertManager.getCapabilityTools();

        // 6. MCP 客户端：启动所有启用的 MCP Server
        this.mcpClientManager = new McpClientManager();
        if (McpConfigManager.getInstance().hasEnabledServers()) {
            try {
                mcpClientManager.startAll();
                log.info("MCP 客户端已启动");
            } catch (Exception e) {
                log.warn("MCP 启动失败（不影响其他功能）: {}", e.getMessage());
            }
        } else {
            log.info("没有启用的 MCP 服务器，跳过 MCP 初始化");
        }

        // 7. 视觉预处理器（复用轻量模型，避免阻塞主模型）
        this.visionPreprocessor = new VisionPreprocessor(
                modelFactory.createLightChatModel(), tokenTracker);

        // 8. 模型执行重试配置（供普通模式 orchestrator、执行子智能体等共用）
        this.modelExecConfig = ExecutionConfig.builder()
                .timeout(Duration.ofSeconds(config.getReadTimeoutSeconds()))
                .maxAttempts(config.getRetryMaxAttempts())
                .initialBackoff(Duration.ofSeconds(config.getRetryInitialBackoffSeconds()))
                .maxBackoff(Duration.ofSeconds(config.getRetryMaxBackoffSeconds()))
                .backoffMultiplier(2.0)
                .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                .build();
        log.info("模型重试配置 — maxAttempts: {}, initialBackoff: {}s, maxBackoff: {}s",
                config.getRetryMaxAttempts(), config.getRetryInitialBackoffSeconds(),
                config.getRetryMaxBackoffSeconds());

        log.info("========== AgentRuntime 基础设施初始化完成 ==========");
    }

    // ==================== 基础组件 Getter ====================

    public ModelFactory getModelFactory() { return modelFactory; }
    public TokenTracker getTokenTracker() { return tokenTracker; }
    public MemoryManager getMemoryManager() { return memoryManager; }
    public ExpertManager getExpertManager() { return expertManager; }
    public KnowledgeExpert getKnowledgeExpert() { return knowledgeExpert; }
    public McpClientManager getMcpClientManager() { return mcpClientManager; }
    public VisionPreprocessor getVisionPreprocessor() { return visionPreprocessor; }
    public PlaywrightBrowserManager getBrowserManager() { return browserManager; }
    public Map<String, Object> getCapabilityTools() { return capabilityTools; }
    public ExecutionConfig getModelExecConfig() { return modelExecConfig; }

    // ==================== 共享状态：知识库文档勾选 ====================

    /** 设置用户勾选的知识库文档集合（null 视为空） */
    public void setSelectedKnowledgeDocs(Set<String> docs) {
        this.selectedKnowledgeDocs = docs != null ? Set.copyOf(docs) : Set.of();
        log.info("知识库选中文档: {}", this.selectedKnowledgeDocs.isEmpty() ? "无" : this.selectedKnowledgeDocs);
    }

    /** 读取当前勾选的知识库文档集合（不可变快照） */
    public Set<String> getSelectedKnowledgeDocs() {
        return selectedKnowledgeDocs;
    }

    // ==================== 共享工具方法：消息构造 ====================

    /**
     * 把用户输入 + 附件组装为一条 AgentScope {@link Msg}，支持多模态。
     *
     * <p>附件处理策略：
     * <ul>
     *   <li>图片 → Base64 编码的 {@link ImageBlock}</li>
     *   <li>文本文档 → UTF-8 读取追加到 {@link TextBlock}（超长截断）</li>
     *   <li>PDF → PDFBox 提取文本追加到 {@link TextBlock}（超长截断）</li>
     *   <li>其他 → 跳过并打警告</li>
     * </ul>
     *
     * @param userInput   用户文本输入
     * @param attachments 附件列表，可为 null 或空
     * @return 组装好的 {@link Msg}（role=USER）
     */
    public Msg buildUserMsg(String userInput, List<File> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            // 无附件：走纯文本路径
            return Msg.builder()
                    .role(MsgRole.USER)
                    .name("user")
                    .textContent(userInput)
                    .build();
        }

        // 多模态：组合 ImageBlock + TextBlock（文本包含用户输入 + 文档正文）
        List<ContentBlock> contentBlocks = new ArrayList<>();
        StringBuilder docTextBuilder = new StringBuilder();

        for (File file : attachments) {
            if (ChatMessage.isImageFile(file)) {
                ImageBlock imageBlock = buildImageBlock(file);
                if (imageBlock != null) {
                    contentBlocks.add(imageBlock);
                    log.info("已添加图片附件: {} ({})", file.getName(), getMediaType(file));
                }
            } else if (ChatMessage.isTextDocument(file)) {
                String docContent = readTextDocument(file);
                if (docContent != null) {
                    docTextBuilder.append("\n\n--- 文档: ").append(file.getName()).append(" ---\n");
                    docTextBuilder.append(docContent);
                    log.info("已添加文本文档附件: {}，内容长度: {} 字符", file.getName(), docContent.length());
                }
            } else if (ChatMessage.isPdfFile(file)) {
                String pdfContent = extractPdfText(file);
                if (pdfContent != null) {
                    docTextBuilder.append("\n\n--- PDF文档: ").append(file.getName()).append(" ---\n");
                    docTextBuilder.append(pdfContent);
                    log.info("已添加PDF文档附件: {}，提取文本长度: {} 字符", file.getName(), pdfContent.length());
                }
            } else {
                log.warn("不支持的附件类型: {}，已跳过", file.getName());
            }
        }

        // 文本块需排在最前，保证模型优先读到用户意图
        String fullText = docTextBuilder.isEmpty() ? userInput : userInput + docTextBuilder;
        contentBlocks.addFirst(TextBlock.builder().text(fullText).build());

        return Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .content(contentBlocks)
                .build();
    }

    /** 判断附件列表是否包含至少一张图片（视觉预处理门槛） */
    public boolean hasImageAttachment(List<File> attachments) {
        if (attachments == null || attachments.isEmpty()) return false;
        for (File f : attachments) {
            if (ChatMessage.isImageFile(f)) return true;
        }
        return false;
    }

    /** 图片文件 → Base64 编码的 ImageBlock（失败返回 null） */
    private ImageBlock buildImageBlock(File imageFile) {
        try {
            byte[] imageBytes = Files.readAllBytes(imageFile.toPath());
            String base64Data = Base64.getEncoder().encodeToString(imageBytes);
            String mediaType = getMediaType(imageFile);
            return ImageBlock.builder()
                    .source(Base64Source.builder()
                            .mediaType(mediaType)
                            .data(base64Data)
                            .build())
                    .build();
        } catch (IOException e) {
            log.error("读取图片文件失败: {}", imageFile.getName(), e);
            return null;
        }
    }

    /** 文本文档 → UTF-8 字符串（超长截断） */
    private String readTextDocument(File file) {
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (content.length() > MAX_TEXT_DOC_CHARS) {
                content = content.substring(0, MAX_TEXT_DOC_CHARS)
                        + "\n... (文档内容已截断，共 " + content.length() + " 字符)";
            }
            return content;
        } catch (IOException e) {
            log.error("读取文本文档失败: {}", file.getName(), e);
            return null;
        }
    }

    /** PDF → 正文文本（超长截断） */
    private String extractPdfText(File pdfFile) {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text.length() > MAX_PDF_CHARS) {
                text = text.substring(0, MAX_PDF_CHARS)
                        + "\n... (PDF内容已截断，共 " + text.length() + " 字符)";
            }
            return text;
        } catch (IOException e) {
            log.error("提取PDF文本失败: {}", pdfFile.getName(), e);
            return null;
        }
    }

    /** 文件扩展名 → MIME 类型 */
    private String getMediaType(File file) {
        String ext = ChatMessage.getFileExtension(file).toLowerCase();
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    // ==================== 共享工具方法：RAG 上下文注入 ====================

    /**
     * 按用户勾选的文档检索知识库，将相关片段注入到用户输入前方。
     *
     * <p>注入格式为 {@code context → question}：知识片段在前、原始问题在后，
     * 提升模型对参考资料的关注度。未勾选任何文档、RAG 未启用或检索异常时
     * 返回原始输入。</p>
     *
     * @param userInput 原始用户输入
     * @return 增强后的用户输入（可能与原输入相同）
     */
    public String enrichWithKnowledge(String userInput) {
        if (!knowledgeExpert.isRagEnabled() || !knowledgeExpert.hasDocuments()) {
            return userInput;
        }
        Set<String> selected = selectedKnowledgeDocs;
        if (selected == null || selected.isEmpty()) {
            return userInput;
        }
        try {
            String context = knowledgeExpert.retrieveContext(userInput, selected);
            if (context != null) {
                log.info("已注入知识库上下文到用户消息（选中 {} 个文档）", selected.size());
                return context + "\n" + userInput;
            }
        } catch (Exception e) {
            log.warn("知识库检索失败，使用原始输入", e);
        }
        return userInput;
    }

    // ==================== 共享工具方法：错误信息提取 ====================

    /**
     * 从异常链中提炼一条人类可读的错误文案。
     *
     * <p>会沿 cause 链走到最底层，再根据常见的网络异常类型和 HTTP 状态码
     * 关键字翻译为中文提示。未匹配到任何规则时返回原始 message。</p>
     */
    public String extractErrorMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause.getClass().getSimpleName();
        }

        // 网络连接类错误
        if (cause instanceof java.net.ConnectException) {
            return "无法连接到模型服务，请确认 " + AgentConfig.getInstance().getBaseUrl() + " 是否已启动";
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return "请求超时，模型响应时间过长，可在设置中调大超时时间";
        }
        if (cause instanceof java.net.UnknownHostException) {
            return "无法解析服务器地址，请检查网络连接和 API 地址配置";
        }
        if (cause instanceof javax.net.ssl.SSLException) {
            return "SSL 连接失败，请检查 API 地址是否正确";
        }

        // HTTP 状态码关键字
        String lowerMsg = message.toLowerCase();
        if (lowerMsg.contains("401") || lowerMsg.contains("unauthorized")) {
            return "API 密钥无效或已过期，请在设置中检查密钥配置";
        }
        if (lowerMsg.contains("429") || lowerMsg.contains("rate limit") || lowerMsg.contains("rate_limit")) {
            return "请求频率超限，请稍后再试";
        }
        if (lowerMsg.contains("insufficient_quota") || lowerMsg.contains("quota")) {
            return "API 额度不足，请检查账户余额";
        }
        if (lowerMsg.contains("model") && lowerMsg.contains("not found")) {
            return "模型名称不存在，请在设置中确认模型名称是否正确";
        }
        if (lowerMsg.contains("500") || lowerMsg.contains("internal server error")) {
            return "模型服务内部错误，请稍后重试";
        }
        if (lowerMsg.contains("502") || lowerMsg.contains("bad gateway")) {
            return "模型服务网关错误，服务可能正在重启，请稍后重试";
        }
        if (lowerMsg.contains("503") || lowerMsg.contains("service unavailable")) {
            return "模型服务暂时不可用，请稍后重试";
        }

        return message;
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭共享资源。
     *
     * <p>目前仅负责断开 MCP 连接；各模式的清理由各自 Service 负责（保持职责分离）。</p>
     */
    public void shutdown() {
        log.info("正在关闭 AgentRuntime...");
        mcpClientManager.stopAll();
        log.info("AgentRuntime 已关闭");
    }
}
