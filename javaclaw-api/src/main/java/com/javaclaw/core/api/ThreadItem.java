package com.javaclaw.core.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Tagged item union shared by persistence, runtime and client projections. */
public sealed interface ThreadItem
        permits ThreadItem.UserMessage,
                ThreadItem.AgentMessage,
                ThreadItem.ReasoningSummary,
                ThreadItem.Plan,
                ThreadItem.CommandExecution,
                ThreadItem.FileChange,
                ThreadItem.McpToolCall,
                ThreadItem.DynamicToolCall,
                ThreadItem.ApprovalRequest,
                ThreadItem.UserInputRequest,
                ThreadItem.UserInputResponse,
                ThreadItem.SubagentCall,
                ThreadItem.WebSearch,
                ThreadItem.ImageView,
                ThreadItem.ContextUsage,
                ThreadItem.ContextCompaction,
                ThreadItem.PromptDraft,
                ThreadItem.Checkpoint,
                ThreadItem.Evaluation,
                ThreadItem.Artifact,
                ThreadItem.EffectReceipt,
                ThreadItem.ErrorItem {

    /** 返回稳定的持久/协议类型标记；客户端应忽略未知类型而不是中断恢复。 */
    String kind();

    /**
     * 已提交的自动化恢复点；状态与进展必须来自执行器，不能由模型直接声明。
     *
     * @param executionId 一次逻辑执行的稳定标识，恢复 Turn 沿用
     * @param definitionHash 本次定义的 SHA-256，变更后不得沿用旧审批
     * @param stepId 下一待执行步骤，完成时为终点
     * @param status RUNNING、WAITING、EXHAUSTED、INTERRUPTED 或 COMPLETED
     * @param iteration 已执行步骤或迭代次数
     * @param outputs 有界步骤结果及恢复变量，不保存凭据
     * @param completedSteps 已确认完成的步骤标识
     * @param usedModelCalls 累计已消费模型调用次数
     * @param usedTokens 累计已消费 token
     * @param elapsedMillis 累计活动执行时长，单位毫秒
     * @param summary 面向用户的进展及剩余条件
     */
    record Checkpoint(
            String executionId,
            String definitionHash,
            String stepId,
            String status,
            int iteration,
            Map<String, String> outputs,
            List<String> completedSteps,
            int usedModelCalls,
            long usedTokens,
            long elapsedMillis,
            String summary)
            implements ThreadItem {
        /** 固定恢复数据并限制其大小；检查点不授予权限，也不重置预算。 */
        public Checkpoint {
            executionId = ThreadId.required(executionId, "executionId");
            definitionHash = ThreadId.required(definitionHash, "definitionHash");
            stepId = ThreadId.required(stepId, "stepId");
            status = ThreadId.required(status, "status");
            if (!definitionHash.matches("[a-f0-9]{64}")
                    || !java.util.Set.of("RUNNING", "WAITING", "EXHAUSTED", "INTERRUPTED", "COMPLETED")
                            .contains(status)
                    || iteration < 0
                    || usedModelCalls < 0
                    || usedTokens < 0
                    || elapsedMillis < 0) {
                throw new IllegalArgumentException("invalid execution checkpoint");
            }
            outputs = Map.copyOf(outputs);
            completedSteps = List.copyOf(completedSteps);
            summary = Objects.requireNonNull(summary, "summary");
            if (outputs.size() > 256
                    || completedSteps.size() > 1_000
                    || outputs.entrySet().stream()
                                    .mapToLong(entry -> entry.getKey().length()
                                            + entry.getValue().length())
                                    .sum()
                            > 262_144) {
                throw new IllegalArgumentException("checkpoint exceeds its bounded capacity");
            }
        }

        @Override
        public String kind() {
            return "checkpoint";
        }
    }

    /**
     * 统一评估结论；passed 只有在证据通过核验时才能为 true。
     *
     * @param scope 被评估步骤或规格版本
     * @param passed 是否满足全部验收条件
     * @param summary 结论与未完成条件
     * @param evidenceItemIds 实际存在的证据 Item 标识，不接受虚构引用
     * @param remaining 未满足条件；允许空列表
     */
    record Evaluation(
            String scope, boolean passed, String summary, List<String> evidenceItemIds, List<String> remaining)
            implements ThreadItem {
        /** 复制证据与剩余条件；没有证据的模型自述不构成完成。 */
        public Evaluation {
            scope = ThreadId.required(scope, "scope");
            summary = Objects.requireNonNull(summary, "summary");
            evidenceItemIds = List.copyOf(evidenceItemIds);
            remaining = List.copyOf(remaining);
            if (passed && (evidenceItemIds.isEmpty() || !remaining.isEmpty())) {
                throw new IllegalArgumentException("a passed evaluation requires evidence and no remaining criteria");
            }
        }

        @Override
        public String kind() {
            return "evaluation";
        }
    }

    /**
     * 领域产物的不可变版本；规格、设计、任务和提案均可复用，不表示产物已实施。
     *
     * @param artifactId 稳定产物标识
     * @param category 产物领域及阶段
     * @param name 展示名称
     * @param revision 产物版本，从 1 开始
     * @param content 有界文本正文，不能含秘密
     * @param sources 来源 Item 标识
     */
    record Artifact(
            String artifactId, String category, String name, long revision, String content, List<String> sources)
            implements ThreadItem {
        /** 校验版本和最大正文；大文件使用附件引用，不写入无限事件载荷。 */
        public Artifact {
            artifactId = ThreadId.required(artifactId, "artifactId");
            category = ThreadId.required(category, "category");
            name = ThreadId.required(name, "name");
            content = Objects.requireNonNull(content, "content");
            sources = List.copyOf(sources);
            if (revision < 1 || content.length() > 262_144) {
                throw new IllegalArgumentException("invalid artifact revision or size");
            }
        }

        @Override
        public String kind() {
            return "artifact";
        }
    }

    /**
     * 副作用执行凭据；PENDING/UNKNOWN 永远不能自动重放，CONFIRMED 可返回同一脱敏结果。
     *
     * @param key 逻辑执行、步骤、工具版本和参数共同确定的内容地址
     * @param tool 实际工具名
     * @param state PENDING、CONFIRMED 或 UNKNOWN
     * @param result 已确认的脱敏工具结果，其他状态为 null
     * @param summary 可展示状态说明，不保存原始参数或凭据
     */
    record EffectReceipt(String key, String tool, String state, ToolExecutionResult result, String summary)
            implements ThreadItem {
        /** 禁止嵌套凭据及无结果的成功状态；结果未知不是失败后可自由重试。 */
        public EffectReceipt {
            key = ThreadId.required(key, "key");
            tool = ThreadId.required(tool, "tool");
            summary = Objects.requireNonNull(summary, "summary");
            if (!key.matches("[a-f0-9]{64}")
                    || !java.util.Set.of("PENDING", "CONFIRMED", "UNKNOWN").contains(state)
                    || ("CONFIRMED".equals(state) != (result != null))
                    || (result != null && result.item() instanceof EffectReceipt)) {
                throw new IllegalArgumentException("invalid effect receipt");
            }
        }

        @Override
        public String kind() {
            return "effectReceipt";
        }
    }

    /**
     * 用户输入的文本与内容寻址附件 Item；历史只保存引用，不把图片二进制存入事件。
     *
     * @param text 非空文本，允许空字符串
     * @param attachments 不可变附件引用列表；不得含本地路径或明文凭据，最多二十项
     */
    record UserMessage(String text, List<TurnInput.AttachmentRef> attachments) implements ThreadItem {
        /** 创建没有附件的文本消息；保留既有文本 Item 的持久及协议表示。 */
        public UserMessage(String text) {
            this(text, List.of());
        }

        /** 要求文本引用非空；保留原文空白，不进行内容改写。 */
        public UserMessage {
            text = Objects.requireNonNull(text, "text");
            attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments"));
            if (attachments.size() > 20) {
                throw new IllegalArgumentException("a user message can contain at most twenty attachments");
            }
        }

        @Override
        public String kind() {
            return "userMessage";
        }
    }

    /**
     * 助手生成的最终文本 Item，与高频 delta 分开持久化。
     *
     * @param text 非空文本，允许空字符串
     */
    record AgentMessage(String text) implements ThreadItem {
        /** 要求文本引用非空；保留原文空白，不进行内容改写。 */
        public AgentMessage {
            text = Objects.requireNonNull(text, "text");
        }

        @Override
        public String kind() {
            return "agentMessage";
        }
    }

    /**
     * 仅保存可展示的推理摘要，不包含模型原始思维链。
     *
     * @param text 非空文本，允许空字符串
     */
    record ReasoningSummary(String text) implements ThreadItem {
        /** 要求文本引用非空；保留原文空白，不进行内容改写。 */
        public ReasoningSummary {
            text = Objects.requireNonNull(text, "text");
        }

        @Override
        public String kind() {
            return "reasoningSummary";
        }
    }

    /**
     * 可恢复的结构化计划 Item，按步骤顺序展示进度。
     *
     * @param steps 非空步骤列表，构造时复制
     * @param details 目标、范围和验收等补充信息；旧记录使用 EMPTY，不改变旧 Wire 载荷
     */
    record Plan(List<PlanStep> steps, PlanDetails details) implements ThreadItem {
        /** 从已有步骤投影创建计划，不推测旧历史中不存在的目标或验收。 */
        public Plan(List<PlanStep> steps) {
            this(steps, PlanDetails.EMPTY);
        }

        /** 复制计划步骤，防止渲染或执行方改变已提交的计划。 */
        public Plan {
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            details = details == null ? PlanDetails.EMPTY : details;
        }

        @Override
        public String kind() {
            return "plan";
        }

        /**
         * 计划的结构化审阅信息；不能把本记录存在当作执行完成。
         *
         * @param goal 用户目标，旧记录可为空字符串
         * @param scope 明确的任务范围
         * @param dependencies 步骤间依赖说明
         * @param acceptanceCriteria 可核验的验收标准
         * @param risks 已识别风险
         * @param openQuestions 需用户决策的问题
         */
        public record PlanDetails(
                String goal,
                String scope,
                List<String> dependencies,
                List<String> acceptanceCriteria,
                List<String> risks,
                List<String> openQuestions) {
            public static final PlanDetails EMPTY = new PlanDetails("", "", List.of(), List.of(), List.of(), List.of());
            /** 复制全部列表，保留空字段而不伪造旧历史缺失的信息。 */
            public PlanDetails {
                goal = Objects.requireNonNull(goal, "goal");
                scope = Objects.requireNonNull(scope, "scope");
                dependencies = List.copyOf(dependencies);
                acceptanceCriteria = List.copyOf(acceptanceCriteria);
                risks = List.copyOf(risks);
                openQuestions = List.copyOf(openQuestions);
            }
        }

        /**
         * 一个具备明确执行状态的计划步骤。
         *
         * @param step 非空白步骤说明
         * @param status 非空执行状态
         */
        public record PlanStep(String step, Status status) {
            /** 计划步骤状态；不等同于 Turn 的整体生命周期。 */
            public enum Status {
                PENDING,
                IN_PROGRESS,
                COMPLETED
            }

            /** 校验步骤文本和状态，保留可用于进度投影的完整步骤。 */
            public PlanStep {
                step = ThreadId.required(step, "step");
                status = Objects.requireNonNull(status, "status");
            }
        }
    }

    /**
     * 提示词优化产物；只是一份待确认草稿，不会自动保存 Profile。
     *
     * @param profileId 被优化的 Profile 标识
     * @param expectedRevision 生成开始时的 Profile 版本，保存时须再次校验
     * @param draft 建议的人设或业务约定正文，不替换固定行为底座
     * @param changes 重要变化说明
     * @param warnings 与实际能力不匹配的提示
     */
    record PromptDraft(
            String profileId, long expectedRevision, String draft, List<String> changes, List<String> warnings)
            implements ThreadItem {
        /** 固定待确认草稿；空草稿或无来源版本不是成功产物。 */
        public PromptDraft {
            profileId = ThreadId.required(profileId, "profileId");
            draft = ThreadId.required(draft, "draft");
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("draft requires a Profile revision");
            }
            changes = List.copyOf(changes);
            warnings = List.copyOf(warnings);
        }

        @Override
        public String kind() {
            return "promptDraft";
        }
    }

    /**
     * 命令执行的有界输出与终止信息；不会记录无限制的进程输出流。
     *
     * @param argv 命令及参数的顺序列表；不经过 Shell 字符串拼接
     * @param exitCode 子进程退出码
     * @param stdout 标准输出；null 归一为空字符串
     * @param stderr 标准错误；null 归一为空字符串
     * @param timedOut 执行是否因超时结束
     * @param truncated 输出是否因大小上限而截断
     */
    record CommandExecution(
            List<String> argv, int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated)
            implements ThreadItem {
        /** 复制 argv 并将缺失的输出归一为空字符串；保留退出码、超时及截断标记。 */
        public CommandExecution {
            argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }

        @Override
        public String kind() {
            return "commandExecution";
        }
    }

    /**
     * 一次受治理文件变更的审计结果。
     *
     * @param path 非空白变更路径标识
     * @param change 非空变更种类
     * @param diff 差异文本；null 归一为空字符串
     */
    record FileChange(String path, ChangeKind change, String diff) implements ThreadItem {
        /** 文件新增、修改或删除的审计分类。 */
        public enum ChangeKind {
            CREATE,
            UPDATE,
            DELETE
        }

        /** 校验路径和变更分类；差异缺失时保存空字符串，不执行文件操作。 */
        public FileChange {
            path = ThreadId.required(path, "path");
            change = Objects.requireNonNull(change, "change");
            diff = diff == null ? "" : diff;
        }

        @Override
        public String kind() {
            return "fileChange";
        }
    }

    /**
     * 外部 MCP 调用的审计 Item，与普通动态工具结果显式区分来源。
     *
     * @param server 非空白 MCP Server 标识
     * @param tool 非空白工具名称
     * @param result 工具结果字段快照；null 归一为空 Map，写入前应完成脱敏
     */
    record McpToolCall(String server, String tool, Map<String, String> result) implements ThreadItem {
        /** 校验工具来源标识并复制结果字段；本类型不执行工具或替代脱敏。 */
        public McpToolCall {
            server = ThreadId.required(server, "server");
            tool = ThreadId.required(tool, "tool");
            result = result == null ? Map.of() : Map.copyOf(result);
        }

        @Override
        public String kind() {
            return "mcpToolCall";
        }
    }

    /**
     * 动态工具调用结果，用于统一 transcript 与模型工具消息投影。
     *
     * @param tool 非空白工具名称
     * @param result 工具结果字段快照；null 归一为空 Map，写入前应完成脱敏
     */
    record DynamicToolCall(String tool, Map<String, String> result) implements ThreadItem {
        /** 校验工具来源标识并复制结果字段；本类型不执行工具或替代脱敏。 */
        public DynamicToolCall {
            tool = ThreadId.required(tool, "tool");
            result = result == null ? Map.of() : Map.copyOf(result);
        }

        @Override
        public String kind() {
            return "dynamicToolCall";
        }
    }

    /**
     * 暂停执行并等待用户决议的审批 Item。
     *
     * @param approvalId 非空白审批请求标识
     * @param reason 非空白审批原因
     * @param risk 非空白风险分类
     */
    record ApprovalRequest(String approvalId, String reason, String risk) implements ThreadItem {
        /** 要求审批标识、原因及风险描述完整，避免客户端显示无法判断的审批。 */
        public ApprovalRequest {
            approvalId = ThreadId.required(approvalId, "approvalId");
            reason = ThreadId.required(reason, "reason");
            risk = ThreadId.required(risk, "risk");
        }

        @Override
        public String kind() {
            return "approvalRequest";
        }
    }

    /**
     * 等待用户回答的问题 Item；可使用自由输入或有限选项。
     *
     * @param requestId 非空白用户输入请求标识
     * @param prompt 非空白问题文本
     * @param choices 最多 100 个非空白选项；null 归一为空列表，空列表表示自由输入
     */
    record UserInputRequest(String requestId, String prompt, List<String> choices) implements ThreadItem {
        /** 复制并校验选项数量与内容，防止超大或不可展示的交互请求。 */
        public UserInputRequest {
            requestId = ThreadId.required(requestId, "requestId");
            prompt = ThreadId.required(prompt, "prompt");
            choices = choices == null ? List.of() : List.copyOf(choices);
            if (choices.size() > 100 || choices.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("user input choices are invalid");
            }
        }

        @Override
        public String kind() {
            return "userInputRequest";
        }
    }

    /**
     * 用户回答或取消的持久 Item，按 requestId 关联问题。
     *
     * @param requestId 非空白用户输入请求标识
     * @param value 回答文本；null 归一为空字符串，cancelled 为 true 时仅保留审计语义
     * @param cancelled 是否取消输入；取消时不应将 value 当作有效回答
     */
    record UserInputResponse(String requestId, String value, boolean cancelled) implements ThreadItem {
        /** 校验请求标识并归一回答；取消结果仍显式保留，不能被误当作正常输入。 */
        public UserInputResponse {
            requestId = ThreadId.required(requestId, "requestId");
            value = value == null ? "" : value;
        }

        @Override
        public String kind() {
            return "userInputResponse";
        }
    }

    /**
     * 父 Thread 可见的子智能体结果摘要，不授予子 Thread 修改父状态的能力。
     *
     * @param childThreadId 非空子 Thread 标识
     * @param task 非空白任务描述
     * @param summary 结果摘要；null 归一为空字符串
     */
    record SubagentCall(ThreadId childThreadId, String task, String summary) implements ThreadItem {
        /** 校验子 Thread 关联与任务说明，允许执行未结束时摘要为空。 */
        public SubagentCall {
            childThreadId = Objects.requireNonNull(childThreadId, "childThreadId");
            task = ThreadId.required(task, "task");
            summary = summary == null ? "" : summary;
        }

        @Override
        public String kind() {
            return "subagentCall";
        }
    }

    /**
     * Web 查询及可审计来源列表，不自动将外部内容当作指令。
     *
     * @param query 非空白查询文本
     * @param sources 来源列表；null 归一为空列表并复制
     */
    record WebSearch(String query, List<String> sources) implements ThreadItem {
        /** 校验查询文本并固定来源列表；不在构造时发起网络请求。 */
        public WebSearch {
            query = ThreadId.required(query, "query");
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        @Override
        public String kind() {
            return "webSearch";
        }
    }

    /**
     * 图片查看的展示记录；引用与描述不包含图片二进制内容。
     *
     * @param uri 非空白图片引用
     * @param description 展示描述；null 归一为空字符串
     */
    record ImageView(String uri, String description) implements ThreadItem {
        /** 校验图片引用并归一可选描述；不读取引用指向的资源。 */
        public ImageView {
            uri = ThreadId.required(uri, "uri");
            description = description == null ? "" : description;
        }

        @Override
        public String kind() {
            return "imageView";
        }
    }

    /**
     * Auditable reference to bounded external context used for this Turn.
     *
     * @param source 非空白上下文来源类别
     * @param sourceId 非空白来源记录标识
     * @param revision 从 1 开始的资源修订号，用于乐观锁
     * @param summary 非空白使用摘要
     */
    record ContextUsage(String source, String sourceId, long revision, String summary) implements ThreadItem {
        /** 校验来源、修订号和摘要，使本 Turn 实际使用的知识版本可审计。 */
        public ContextUsage {
            source = ThreadId.required(source, "source");
            sourceId = ThreadId.required(sourceId, "sourceId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            summary = ThreadId.required(summary, "summary");
        }

        @Override
        public String kind() {
            return "contextUsage";
        }
    }

    /** 上下文压缩的可见生命周期标记；摘要或 opaque Provider 载荷仅存在 ConversationWindow。 */
    record ContextCompaction() implements ThreadItem {
        @Override
        public String kind() {
            return "contextCompaction";
        }
    }

    /**
     * 可展示、可持久化的错误摘要；调用方必须先移除凭据和敏感输出。
     *
     * @param code 非空白稳定错误代码
     * @param message 非空白脱敏错误说明
     * @param retryable 调用方是否可以在相同权限约束下重试
     */
    record ErrorItem(String code, String message, boolean retryable) implements ThreadItem {
        /** 要求错误代码和说明非空；retryable 不表示自动授权重试。 */
        public ErrorItem {
            code = ThreadId.required(code, "code");
            message = ThreadId.required(message, "message");
        }

        @Override
        public String kind() {
            return "error";
        }
    }
}
