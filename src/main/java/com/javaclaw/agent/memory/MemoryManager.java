package com.javaclaw.agent.memory;

import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.WorkspaceManager;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一记忆管理器 — 集中管理所有智能体的记忆生命周期
 *
 * <p>核心设计：智能体自身不持久保留记忆，记忆按需注入、用后捕获。</p>
 *
 * <ul>
 *   <li><b>编排器记忆</b>：使用 {@link AutoContextMemory} 提供智能压缩，
 *       在构建编排器时注入，由此管理器创建和持有</li>
 *   <li><b>子智能体记忆快照</b>：子智能体默认无跨调用记忆。
 *       需要时可通过 {@link #injectMemory} 注入历史消息，
 *       调用后通过 {@link #captureMemory} 捕获</li>
 *   <li><b>规划模式记忆</b>：协调者和专家的记忆在讨论期间由此管理器管理，
 *       讨论结束后统一清除</li>
 *   <li><b>会话持久化</b>：通过 {@link JsonSession} 统一保存/恢复编排器状态</li>
 * </ul>
 *
 * @author JavaClaw
 */
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    /** 编排器的 AutoContextMemory（在构造时创建，注入到编排器） */
    private final AutoContextMemory orchestratorMemory;

    /** 子智能体记忆快照（智能体名称 → 消息列表） */
    private final Map<String, List<Msg>> memorySnapshots = new ConcurrentHashMap<>();

    /** clearAll 进行中标志，避免并发 captureMemory 将已清空的快照 put 回来 */
    private volatile boolean clearing = false;

    /** 子智能体快照最大消息数（超出时截断旧消息） */
    private static final int MAX_SNAPSHOT_MESSAGES = 30;

    /** 会话状态持久化 */
    private final JsonSession jsonSession;

    /** AutoContextMemory 配置（用于日志和重建） */
    private final AutoContextConfig autoContextConfig;

    /**
     * 构造统一记忆管理器
     *
     * @param modelFactory 模型工厂（用于创建 AutoContextMemory 的压缩模型）
     */
    public MemoryManager(ModelFactory modelFactory) {
        AgentConfig config = AgentConfig.getInstance();

        // 构建 AutoContextMemory 配置
        this.autoContextConfig = AutoContextConfig.builder()
                .maxToken(config.getMemoryMaxToken())
                .msgThreshold(config.getMemoryMsgThreshold())
                .lastKeep(config.getMemoryLastKeep())
                .tokenRatio(config.getMemoryTokenRatio())
                .build();

        // 创建编排器记忆（带智能压缩）
        this.orchestratorMemory = new AutoContextMemory(autoContextConfig, modelFactory.createChatModel());
        log.info("编排器记忆已创建 — maxToken: {}, msgThreshold: {}, lastKeep: {}, tokenRatio: {}",
                config.getMemoryMaxToken(), config.getMemoryMsgThreshold(),
                config.getMemoryLastKeep(), config.getMemoryTokenRatio());

        // 初始化会话持久化
        Path agentSessionsDir = WorkspaceManager.getInstance()
                .getCurrentWorkspacePath().resolve("data").resolve("agent-sessions");
        this.jsonSession = new JsonSession(agentSessionsDir);
        log.info("会话状态持久化已初始化: {}", agentSessionsDir);
    }

    // ==================== 编排器记忆 ====================

    /**
     * 获取编排器记忆实例（用于构建编排器时注入）
     */
    public AutoContextMemory getOrchestratorMemory() {
        return orchestratorMemory;
    }

    // ==================== 上下文预算管理 ====================

    /**
     * 估算当前编排器记忆的 token 数
     *
     * <p>使用中英文混合估算：平均 2.5 字符/token</p>
     */
    public int estimateOrchestratorTokens() {
        List<Msg> messages = orchestratorMemory.getMessages();
        int totalChars = 0;
        for (Msg msg : messages) {
            String text = msg.getTextContent();
            if (text != null) totalChars += text.length();
        }
        return totalChars * 10 / 25;
    }

    /**
     * 确保上下文预算足够容纳新消息
     *
     * <p>如果当前记忆 + 新输入 + 预留输出超过 maxToken 上限，
     * 触发主动压缩并再次检查。</p>
     *
     * @param newInputChars 新输入的字符数
     * @param reserveTokens 预留给输出的 token 数
     * @return true 如果预算充足，false 如果压缩后仍不足
     */
    public boolean ensureContextBudget(int newInputChars, int reserveTokens) {
        int currentTokens = estimateOrchestratorTokens();
        int newTokens = newInputChars * 10 / 25;
        long maxToken = autoContextConfig.getMaxToken();

        if (currentTokens + newTokens + reserveTokens > maxToken) {
            log.warn("上下文预算不足 — 当前: {}K, 新增: {}K, 预留: {}K, 上限: {}K",
                    currentTokens / 1000, newTokens / 1000, reserveTokens / 1000, maxToken / 1000);
            // 触发主动压缩
            boolean compressed = orchestratorMemory.compressIfNeeded();
            if (compressed) {
                log.info("已触发上下文主动压缩");
            }
            // 再次检查
            int afterCompress = estimateOrchestratorTokens();
            return afterCompress + newTokens + reserveTokens <= maxToken;
        }
        return true;
    }

    // ==================== 子智能体记忆动态注入/捕获 ====================

    /**
     * 向指定智能体注入记忆快照
     *
     * <p>从中央存储中加载该智能体的历史消息，注入到智能体的 Memory 中。
     * 如果没有快照则不做任何操作（智能体以空白状态启动）。</p>
     *
     * @param agent 目标智能体
     */
    public void injectMemory(ReActAgent agent) {
        String name = agent.getName();
        List<Msg> snapshot = memorySnapshots.get(name);
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }

        Memory memory = agent.getMemory();
        memory.clear();
        for (Msg msg : snapshot) {
            memory.addMessage(msg);
        }
        // 注入后自愈：上一轮子智能体若被中途取消/中断，快照可能停在 tool_call 与结果之间，
        // 直接重放会触发网关的"悬空工具调用"拒绝
        healDanglingToolCalls(memory, name);
        log.debug("已注入 {} 条记忆到智能体 [{}]", snapshot.size(), name);
    }

    /**
     * 捕获指定智能体的当前记忆到中央存储
     *
     * @param agent 目标智能体
     */
    public void captureMemory(ReActAgent agent) {
        if (clearing) {
            log.debug("正在执行 clearAll，跳过 captureMemory [{}]", agent.getName());
            return;
        }
        String name = agent.getName();
        List<Msg> messages = agent.getMemory().getMessages();
        if (messages.isEmpty()) {
            memorySnapshots.remove(name);
        } else {
            List<Msg> toStore;
            if (messages.size() > MAX_SNAPSHOT_MESSAGES) {
                toStore = new ArrayList<>(messages.subList(
                        messages.size() - MAX_SNAPSHOT_MESSAGES, messages.size()));
                log.info("智能体 [{}] 快照截断: {} → {} 条", name, messages.size(), MAX_SNAPSHOT_MESSAGES);
            } else {
                toStore = new ArrayList<>(messages);
            }
            memorySnapshots.put(name, toStore);
        }
        log.debug("已捕获智能体 [{}] 的 {} 条记忆", name, messages.size());
    }

    // ==================== 悬空工具调用自愈 ====================

    /**
     * 修复"悬空工具调用"：模型已发出 {@link ToolUseBlock}，但对应的
     * {@link ToolResultBlock} 缺失（典型成因：流式中途取消 / LoopDetectionHook 中断 /
     * 工具执行超时或抛异常 / 会话恢复时上一轮停在 tool_call 与结果之间）。
     *
     * <p>OpenAI 协议要求每个 assistant 的 tool_calls 都必须在历史里有匹配的
     * tool 结果消息，否则网关会以
     * <em>"Pending tool calls exist without results"</em> 拒绝整个请求。</p>
     *
     * <p>本方法为每个无匹配结果的工具调用 id 追加一条 {@code role=TOOL} 的合成空结果，
     * 使历史自洽；幂等——历史本就干净时不做任何改动。</p>
     *
     * @param memory    目标记忆
     * @param agentName 智能体名（用于日志与合成消息归属）
     * @return 补齐的悬空调用数量（0 表示无需修复）
     */
    public int healDanglingToolCalls(Memory memory, String agentName) {
        if (memory == null) {
            return 0;
        }
        List<Msg> messages = memory.getMessages();
        if (messages.isEmpty()) {
            return 0;
        }

        // 1. 收集已存在的工具结果 id
        Set<String> resolved = new HashSet<>();
        for (Msg msg : messages) {
            for (ToolResultBlock rb : msg.getContentBlocks(ToolResultBlock.class)) {
                if (rb.getId() != null) {
                    resolved.add(rb.getId());
                }
            }
        }

        // 2. 找出无匹配结果的工具调用（按出现顺序去重）
        LinkedHashMap<String, String> dangling = new LinkedHashMap<>(); // id -> toolName
        for (Msg msg : messages) {
            for (ToolUseBlock ub : msg.getContentBlocks(ToolUseBlock.class)) {
                String id = ub.getId();
                if (id != null && !resolved.contains(id) && !dangling.containsKey(id)) {
                    dangling.put(id, ub.getName());
                }
            }
        }
        if (dangling.isEmpty()) {
            return 0;
        }

        // 3. 为每个悬空调用追加一条合成空结果，使历史自洽
        for (Map.Entry<String, String> e : dangling.entrySet()) {
            ToolResultBlock filler = ToolResultBlock
                    .text("[已取消] 上一轮该工具调用未完成，本次无结果。")
                    .withIdAndName(e.getKey(), e.getValue());
            Msg toolMsg = Msg.builder()
                    .role(MsgRole.TOOL)
                    .name(agentName)
                    .content(filler)
                    .build();
            memory.addMessage(toolMsg);
        }
        log.info("智能体 [{}] 修复 {} 个悬空工具调用: {}", agentName, dangling.size(), dangling.keySet());
        return dangling.size();
    }

    /**
     * 修复编排器共享记忆中的悬空工具调用。每轮发送给模型前调用，是该问题的单一收敛点。
     *
     * @return 补齐的悬空调用数量
     */
    public int healOrchestratorDanglingToolCalls() {
        return healDanglingToolCalls(orchestratorMemory, "orchestrator");
    }

    /**
     * 清除指定智能体的记忆快照和智能体本身的记忆
     *
     * @param agent 目标智能体
     */
    public void clearAgentMemory(ReActAgent agent) {
        String name = agent.getName();
        memorySnapshots.remove(name);
        if (agent.getMemory() != null) {
            agent.getMemory().clear();
        }
        log.debug("已清除智能体 [{}] 的记忆", name);
    }

    // ==================== 全局记忆操作 ====================

    /**
     * 清除所有记忆（编排器 + 所有子智能体快照）
     */
    public void clearAll() {
        clearing = true;
        try {
            orchestratorMemory.clear();
            memorySnapshots.clear();
        } finally {
            clearing = false;
        }
        log.info("所有智能体记忆已清除");
    }

    /**
     * 清除所有子智能体的记忆快照（不影响编排器记忆）
     */
    public void clearAllSnapshots() {
        memorySnapshots.clear();
        log.info("所有子智能体记忆快照已清除");
    }

    /**
     * 获取指定智能体的记忆快照（只读）
     *
     * @param agentName 智能体名称
     * @return 消息列表，无快照时返回空列表
     */
    public List<Msg> getSnapshot(String agentName) {
        List<Msg> snapshot = memorySnapshots.get(agentName);
        return snapshot != null ? List.copyOf(snapshot) : List.of();
    }

    /**
     * 获取所有有快照的智能体名称
     */
    public List<String> getSnapshotAgentNames() {
        return new ArrayList<>(memorySnapshots.keySet());
    }

    // ==================== 会话持久化 ====================

    /**
     * 保存编排器状态到指定会话
     *
     * @param sessionId    会话 ID
     * @param orchestrator 编排器智能体
     */
    public void saveSession(String sessionId, ReActAgent orchestrator) {
        try {
            SessionManager.forSessionId(sessionId)
                    .withSession(jsonSession)
                    .addComponent(orchestrator)
                    .saveSession();
            log.info("智能体状态已保存到会话: {}", sessionId);
        } catch (Exception e) {
            log.error("保存智能体会话状态失败: {}", sessionId, e);
        }
    }

    /**
     * 从指定会话恢复编排器状态
     *
     * @param sessionId    会话 ID
     * @param orchestrator 编排器智能体
     */
    public void loadSession(String sessionId, ReActAgent orchestrator) {
        try {
            SessionManager.forSessionId(sessionId)
                    .withSession(jsonSession)
                    .addComponent(orchestrator)
                    .loadIfExists();
            log.info("智能体状态已从会话恢复: {}", sessionId);
        } catch (Exception e) {
            log.warn("恢复智能体会话状态失败（将使用空白状态）: {}", sessionId, e);
        }
    }

    /**
     * 删除指定会话的智能体状态
     *
     * @param sessionId 会话 ID
     */
    public void deleteSession(String sessionId) {
        try {
            SessionManager.forSessionId(sessionId)
                    .withSession(jsonSession)
                    .deleteIfExists();
            log.info("智能体会话状态已删除: {}", sessionId);
        } catch (Exception e) {
            log.error("删除智能体会话状态失败: {}", sessionId, e);
        }
    }
}
