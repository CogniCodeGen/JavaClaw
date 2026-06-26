package com.javaclaw.system;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 命令行执行工具集
 *
 * <p>为命令行专家提供 Shell 命令执行能力，并实现以下安全机制：</p>
 * <ul>
 *   <li><b>文件操作拦截</b>：严格禁止 rm/cp/mv/mkdir/touch 等文件管理命令，
 *       文件操作必须通过系统操作专家（system_expert）完成</li>
 *   <li><b>高风险命令确认</b>：sudo/chmod/kill 等危险命令首次执行前弹出确认对话框</li>
 *   <li><b>白名单自动记忆</b>：用户确认后自动加入白名单，后续无需再次确认</li>
 * </ul>
 *
 * @author JavaClaw
 */
public class CommandLineTools {

    private static final Logger log = LoggerFactory.getLogger(CommandLineTools.class);

    /** 严格禁止的文件操作命令（必须通过 system_expert 处理） */
    private static final Set<String> BLOCKED_FILE_COMMANDS = Set.of(
            "rm", "cp", "mv", "ln", "touch", "mkdir", "rmdir",
            "install", "rsync", "scp", "dd", "truncate", "shred",
            "mkfile", "ditto"  // macOS 特有
    );

    /** 需要用户确认或白名单的高风险命令前缀 */
    private static final Set<String> HIGH_RISK_PREFIXES = Set.of(
            "sudo", "su", "chmod", "chown", "chflags",
            "kill", "pkill", "killall",
            "shutdown", "reboot", "halt", "poweroff", "init",
            "systemctl", "launchctl", "service",
            "crontab", "nohup"
    );

    /** 命令执行超时（秒） */
    private static final int EXEC_TIMEOUT_SECONDS = 60;

    /** 输出截断：保留头部行数（构建/测试输出的开头通常是环境与配置信息） */
    private static final int HEAD_OUTPUT_LINES = 100;
    /** 输出截断：保留尾部行数（Maven/Gradle 等构建工具的错误信息在尾部，必须保住） */
    private static final int TAIL_OUTPUT_LINES = 200;
    /** 输出总字符上限：工具结果会驻留在 ReAct 上下文中、每轮迭代全量重发，单条过胖会被放大数倍 */
    private static final int MAX_OUTPUT_CHARS = 16_000;

    private final CommandWhitelistManager whitelist = CommandWhitelistManager.getInstance();
    private final CommandSessionManager sessionMgr = CommandSessionManager.getInstance();

    // ==================== 工具方法 ====================

    @Tool(name = "cmd_execute",
            description = "在指定工作目录执行 Shell 命令并返回输出结果。" +
                    "【严格限制】禁止执行文件操作命令（rm/cp/mv/mkdir/touch/ln/rsync 等），" +
                    "文件的创建、复制、移动、删除必须通过系统操作专家（system_expert）完成。" +
                    "高风险命令（sudo/kill/chmod 等）首次执行需用户确认，确认后自动加入白名单，后续无需再次确认。" +
                    "支持的命令类型：编译构建（mvn/gradle/npm）、版本控制（git）、" +
                    "进程查看（ps/top）、网络诊断（ping/curl）、脚本执行（python/node）等。")
    public String executeCommand(
            @ToolParam(name = "command", description = "要执行的 Shell 命令") String command,
            @ToolParam(name = "work_dir",
                    description = "命令执行的工作目录（绝对路径），留空则使用用户主目录") String workDir) {

        log.info("命令执行工具: {} @ {}", command, workDir);

        if (command == null || command.isBlank()) {
            return ToolResponse.error("cmd_execute", "命令不能为空");
        }

        String trimmedCmd = command.trim();

        String effectiveWorkDir = resolveWorkDir(workDir);
        if (effectiveWorkDir == null) {
            return ToolResponse.error("cmd_execute", "工作目录不存在或无效: " + workDir);
        }

        SecurityCheck chk = checkBeforeExec(trimmedCmd, effectiveWorkDir);
        if (!chk.ok()) {
            return ToolResponse.error("cmd_execute", chk.denyReason());
        }
        return doExecute(trimmedCmd, effectiveWorkDir);
    }

    @Tool(name = "cmd_whitelist_list",
            description = "列出命令白名单中所有已批准的高风险命令条目。")
    public String listWhitelist() {
        List<CommandWhitelistManager.WhitelistEntry> entries = whitelist.listEntries();
        if (entries.isEmpty()) {
            return ToolResponse.success("cmd_whitelist_list",
                    "白名单为空。高风险命令首次执行经用户确认后将自动加入白名单。");
        }

        StringBuilder sb = new StringBuilder("命令白名单（共 ").append(entries.size()).append(" 条）：\n\n");
        for (CommandWhitelistManager.WhitelistEntry e : entries) {
            sb.append("ID: ").append(e.id()).append("\n");
            sb.append("  命令前缀: ").append(e.commandPrefix()).append("\n");
            sb.append("  工作目录: ").append(
                    e.workDir().isBlank() ? "（所有目录）" : e.workDir()).append("\n");
            sb.append("  添加时间: ").append(e.addedAt()).append("\n");
            sb.append("  已使用:   ").append(e.useCount()).append(" 次\n\n");
        }
        return ToolResponse.success("cmd_whitelist_list", sb.toString().trim());
    }

    @Tool(name = "cmd_whitelist_add",
            description = "手动将命令前缀添加到白名单。白名单命令在指定目录下执行时无需用户确认。" +
                    "通常由系统在用户首次确认后自动添加，也可手动管理。")
    public String addToWhitelist(
            @ToolParam(name = "command_prefix",
                    description = "命令前缀，如 'git push'、'npm run build'、'sudo systemctl'") String commandPrefix,
            @ToolParam(name = "work_dir",
                    description = "白名单规则生效的工作目录（绝对路径），留空表示所有目录") String workDir,
            @ToolParam(name = "reason", description = "添加理由") String reason) {

        if (commandPrefix == null || commandPrefix.isBlank()) {
            return ToolResponse.error("cmd_whitelist_add", "命令前缀不能为空");
        }

        String effectiveDir = (workDir == null || workDir.isBlank()) ? "" :
                resolveWorkDir(workDir) != null ? resolveWorkDir(workDir) : workDir.trim();

        String id = whitelist.addEntry(commandPrefix.trim(), effectiveDir);
        return ToolResponse.success("cmd_whitelist_add",
                "已添加白名单条目 (id=" + id + ")：\n" +
                "命令前缀: " + commandPrefix.trim() + "\n" +
                "工作目录: " + (effectiveDir.isBlank() ? "（所有目录）" : effectiveDir) + "\n" +
                "理由: " + (reason != null && !reason.isBlank() ? reason : "未说明"));
    }

    @Tool(name = "cmd_whitelist_remove",
            description = "从白名单移除指定条目（使用 cmd_whitelist_list 获取条目 ID）。")
    public String removeFromWhitelist(
            @ToolParam(name = "entry_id", description = "白名单条目 ID") String entryId) {

        if (entryId == null || entryId.isBlank()) {
            return ToolResponse.error("cmd_whitelist_remove", "条目 ID 不能为空");
        }
        boolean removed = whitelist.removeEntry(entryId.trim());
        if (removed) {
            return ToolResponse.success("cmd_whitelist_remove",
                    "已移除白名单条目: " + entryId.trim());
        } else {
            return ToolResponse.error("cmd_whitelist_remove",
                    "未找到白名单条目: " + entryId.trim() + "，请用 cmd_whitelist_list 查看有效 ID。");
        }
    }

    // ==================== 命令行会话（多轮交互） ====================

    @Tool(name = "cmd_session_open",
            description = "创建一个长生命周期的命令行会话窗口（独立 Shell 进程），返回会话 ID 供后续多次交互。" +
                    "适用于：需要在同一个 Shell 上下文中连续执行多条命令（保留 cd/环境变量/激活的虚拟环境）、" +
                    "或与命令行交互式程序（如 python -i、psql、mysql、ftp、bc 等）进行多轮对话。" +
                    "单次性命令仍建议用 cmd_execute。最多并发 " + CommandSessionManager.MAX_SESSIONS +
                    " 个会话；空闲 30 分钟自动回收。" +
                    "注意：底层没有 PTY，强 TTY 依赖的程序（vim/top/less 等）可能无法正常工作。")
    public String openSession(
            @ToolParam(name = "work_dir",
                    description = "会话初始工作目录（绝对路径），留空则使用用户主目录") String workDir) {

        String effectiveWorkDir = resolveWorkDir(workDir);
        if (effectiveWorkDir == null) {
            return ToolResponse.error("cmd_session_open", "工作目录不存在或无效: " + workDir);
        }
        try {
            CommandSessionManager.ShellSession s = sessionMgr.open(effectiveWorkDir);
            log.info("会话已开启: {} @ {}", s.id(), effectiveWorkDir);
            return ToolResponse.success("cmd_session_open",
                    "已创建命令行会话\n" +
                    "session_id: " + s.id() + "\n" +
                    "work_dir: " + effectiveWorkDir + "\n" +
                    "提示：用 cmd_session_exec 执行结构化命令（带退出码）；" +
                    "用 cmd_session_input/cmd_session_read 与交互式程序对话；用 cmd_session_close 关闭会话。");
        } catch (IOException e) {
            return ToolResponse.error("cmd_session_open", "创建会话失败: " + e.getMessage());
        }
    }

    @Tool(name = "cmd_session_exec",
            description = "在已有会话中执行一条 Shell 命令，等待命令结束后返回完整输出和退出码。" +
                    "命令在原会话上下文中执行，会话状态（当前目录、shell 变量等）跨调用保留。" +
                    "安全策略与 cmd_execute 完全一致：禁止文件操作命令、高风险命令需用户确认/白名单。" +
                    "适用于需要拿到确定结果的场景；交互式程序的多轮对话请用 cmd_session_input。")
    public String execInSession(
            @ToolParam(name = "session_id", description = "由 cmd_session_open 返回的会话 ID") String sessionId,
            @ToolParam(name = "command", description = "要执行的 Shell 命令") String command,
            @ToolParam(name = "timeout_seconds",
                    description = "命令最长等待时间（秒），默认 60；超时会话保留，命令可能仍在运行") Integer timeoutSeconds) {

        if (sessionId == null || sessionId.isBlank()) {
            return ToolResponse.error("cmd_session_exec", "session_id 不能为空");
        }
        if (command == null || command.isBlank()) {
            return ToolResponse.error("cmd_session_exec", "命令不能为空");
        }
        CommandSessionManager.ShellSession s = sessionMgr.get(sessionId.trim());
        if (s == null) {
            return ToolResponse.error("cmd_session_exec", "会话不存在: " + sessionId + "（可用 cmd_session_list 查看）");
        }
        if (!s.isAlive()) {
            return ToolResponse.error("cmd_session_exec", "会话已退出: " + sessionId);
        }

        String trimmedCmd = command.trim();
        SecurityCheck chk = checkBeforeExec(trimmedCmd, s.workDir());
        if (!chk.ok()) {
            return ToolResponse.error("cmd_session_exec", chk.denyReason());
        }

        int timeout = (timeoutSeconds == null || timeoutSeconds <= 0) ? 60 : Math.min(timeoutSeconds, 600);

        // 生成唯一哨兵，避免与用户输出文本冲突
        String nonce = Long.toString(System.nanoTime(), 36)
                + Integer.toString(ThreadLocalRandom.current().nextInt(0x10000, 0xFFFFF), 36);
        String marker = "__JC_SESS_END_" + nonce + "__";

        // 清空旧缓冲，避免上一次未读输出污染本次匹配
        String leftover = s.drain();
        if (!leftover.isEmpty()) {
            log.debug("会话 {} 残留输出（{} 字符）已被丢弃，避免哨兵匹配错乱", sessionId, leftover.length());
        }

        // 包装：用户命令执行完后 echo 哨兵 + $? 退出码
        String wrapped = trimmedCmd + "\necho \"" + marker + "$?\"\n";
        try {
            s.sendInput(wrapped);
        } catch (IOException e) {
            return ToolResponse.error("cmd_session_exec", "发送命令到会话失败: " + e.getMessage());
        }

        CommandSessionManager.MarkerHit hit;
        try {
            hit = s.waitForMarker(marker, timeout * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResponse.error("cmd_session_exec", "等待会话输出被中断");
        }

        if (hit == null) {
            // 超时：尽量把已累积的输出返回，提示会话仍在
            String partial = s.drain();
            return ToolResponse.timeout("cmd_session_exec", timeout,
                    "命令未在超时内完成；会话仍在运行（session_id=" + sessionId + "）。\n" +
                    "已累积输出（可能不完整）：\n" + (partial.isBlank() ? "（无）" : partial));
        }

        String body = hit.body();
        String exitCode = hit.markerLine().trim(); // markerLine 是哨兵后面的 "0" / "1" 等
        if (exitCode.isEmpty()) exitCode = "?";

        String message = "session_id: " + sessionId + " | exit_code: " + exitCode + "\n" +
                (body.isBlank() ? "（无输出）" : body.stripTrailing());

        return "0".equals(exitCode)
                ? ToolResponse.success("cmd_session_exec", message)
                : ToolResponse.error("cmd_session_exec", message);
    }

    @Tool(name = "cmd_session_input",
            description = "向会话发送任意 stdin 文本（不附加 Shell 哨兵）。" +
                    "适用于与交互式程序对话：例如向 python -i 发送 'print(1+1)\\n'、向 mysql 发送 'SELECT 1;\\n'。" +
                    "本工具不做命令安全检查，调用方负责输入内容；" +
                    "Shell 级危险命令请改用 cmd_session_exec（它会走完整安全栈）。" +
                    "发送完后会等待最多 wait_seconds 秒收集输出。")
    public String sendInputToSession(
            @ToolParam(name = "session_id", description = "会话 ID") String sessionId,
            @ToolParam(name = "input",
                    description = "要写入 stdin 的原始文本；如需提交一行，请自行在末尾加 \\n") String input,
            @ToolParam(name = "wait_seconds",
                    description = "发送后等待输出的最长秒数，默认 5；缺省/0 表示立刻返回当前累积输出") Integer waitSeconds) {

        if (sessionId == null || sessionId.isBlank()) {
            return ToolResponse.error("cmd_session_input", "session_id 不能为空");
        }
        if (input == null) {
            return ToolResponse.error("cmd_session_input", "input 不能为 null（允许空字符串，仅读取当前输出）");
        }
        CommandSessionManager.ShellSession s = sessionMgr.get(sessionId.trim());
        if (s == null) {
            return ToolResponse.error("cmd_session_input", "会话不存在: " + sessionId);
        }
        if (!s.isAlive()) {
            return ToolResponse.error("cmd_session_input", "会话已退出: " + sessionId);
        }

        int wait = (waitSeconds == null || waitSeconds < 0) ? 5 : Math.min(waitSeconds, 120);

        try {
            if (!input.isEmpty()) {
                s.sendInput(input);
            }
        } catch (IOException e) {
            return ToolResponse.error("cmd_session_input", "写入会话 stdin 失败: " + e.getMessage());
        }

        String collected;
        try {
            // 收到第一字节后再静默等 300ms 合并短时多次写入，避免半行返回
            collected = s.waitForAny(wait * 1000L, 300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResponse.error("cmd_session_input", "等待输出被中断");
        }

        if (collected.isEmpty()) {
            return ToolResponse.success("cmd_session_input",
                    "session_id: " + sessionId + " | 已发送 " + input.length() + " 字符，" +
                    wait + " 秒内无新增输出。可继续 cmd_session_read 等待。");
        }
        return ToolResponse.success("cmd_session_input",
                "session_id: " + sessionId + "\n" + collected.stripTrailing());
    }

    @Tool(name = "cmd_session_read",
            description = "读取会话当前累积的输出而不发送任何输入。" +
                    "用于 cmd_session_input 之后命令仍在打字、或需要继续读取分批到来的输出。" +
                    "wait_seconds 内若有任意新增输出会立即返回；为 0 时只取当前缓冲并立即返回。")
    public String readFromSession(
            @ToolParam(name = "session_id", description = "会话 ID") String sessionId,
            @ToolParam(name = "wait_seconds",
                    description = "无输出时最多等待秒数，默认 10；缓冲已有输出时立即返回") Integer waitSeconds) {

        if (sessionId == null || sessionId.isBlank()) {
            return ToolResponse.error("cmd_session_read", "session_id 不能为空");
        }
        CommandSessionManager.ShellSession s = sessionMgr.get(sessionId.trim());
        if (s == null) {
            return ToolResponse.error("cmd_session_read", "会话不存在: " + sessionId);
        }

        int wait = (waitSeconds == null || waitSeconds < 0) ? 10 : Math.min(waitSeconds, 120);
        String collected;
        try {
            collected = s.waitForAny(wait * 1000L, 200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResponse.error("cmd_session_read", "读取被中断");
        }

        String aliveTag = s.isAlive() ? "运行中" : "已退出";
        if (collected.isEmpty()) {
            return ToolResponse.success("cmd_session_read",
                    "session_id: " + sessionId + " | 状态: " + aliveTag + " | " + wait + " 秒内无新增输出。");
        }
        return ToolResponse.success("cmd_session_read",
                "session_id: " + sessionId + " | 状态: " + aliveTag + "\n" + collected.stripTrailing());
    }

    @Tool(name = "cmd_session_close",
            description = "关闭指定命令行会话，销毁底层 Shell 进程。任务完成后应主动关闭以释放资源。")
    public String closeSession(
            @ToolParam(name = "session_id", description = "要关闭的会话 ID") String sessionId) {

        if (sessionId == null || sessionId.isBlank()) {
            return ToolResponse.error("cmd_session_close", "session_id 不能为空");
        }
        boolean removed = sessionMgr.close(sessionId.trim());
        return removed
                ? ToolResponse.success("cmd_session_close", "已关闭会话: " + sessionId)
                : ToolResponse.error("cmd_session_close", "未找到会话: " + sessionId);
    }

    @Tool(name = "cmd_session_list",
            description = "列出当前所有命令行会话的元数据（ID、工作目录、存活状态、缓冲大小、活跃时间）。")
    public String listSessions() {
        var all = sessionMgr.list();
        if (all.isEmpty()) {
            return ToolResponse.success("cmd_session_list",
                    "当前无活动会话。用 cmd_session_open 创建一个。");
        }
        StringBuilder sb = new StringBuilder("命令行会话（共 ").append(all.size()).append(" 个，上限 ")
                .append(CommandSessionManager.MAX_SESSIONS).append("）：\n\n");
        for (CommandSessionManager.ShellSession s : all) {
            sb.append("session_id: ").append(s.id()).append("\n");
            sb.append("  工作目录: ").append(s.workDir()).append("\n");
            sb.append("  状态:    ").append(s.isAlive() ? "运行中" : "已退出").append("\n");
            sb.append("  缓冲:    ").append(s.bufferSize()).append(" 字符（未读取）\n");
            sb.append("  创建时间: ").append(Instant.ofEpochMilli(s.createdAt())).append("\n");
            sb.append("  最后活跃: ").append(Instant.ofEpochMilli(s.lastActivity())).append("\n\n");
        }
        return ToolResponse.success("cmd_session_list", sb.toString().trim());
    }

    // ==================== 内部实现 ====================

    /** 安全检查结果：ok=true 允许执行；ok=false 时 denyReason 为拒绝原因 */
    private record SecurityCheck(boolean ok, String denyReason) {
        static SecurityCheck allow() { return new SecurityCheck(true, null); }
        static SecurityCheck deny(String reason) { return new SecurityCheck(false, reason); }
    }

    /**
     * 命令执行前的统一安全检查
     *
     * <p>由 {@link #executeCommand} 与 {@link #execInSession} 共用：</p>
     * <ol>
     *   <li>文件操作命令硬拦截（rm/cp/mv 等必须走 system_expert）</li>
     *   <li>托管任务：走 {@link ToolConfirmationManager} 统一路径（支持 PlannedAction 免确认）</li>
     *   <li>非托管：高风险命令首次弹窗确认，确认后自动加入白名单</li>
     * </ol>
     */
    private SecurityCheck checkBeforeExec(String trimmedCmd, String effectiveWorkDir) {
        String blockedOp = findBlockedFileOperation(trimmedCmd);
        if (blockedOp != null) {
            return SecurityCheck.deny(
                    "禁止执行文件操作命令「" + blockedOp + "」。\n" +
                    "文件的创建、复制、移动、删除等操作必须通过系统操作专家（system_expert）完成，" +
                    "请将此任务转交给 system_expert。");
        }

        if (ToolConfirmationManager.isInManagedTask()) {
            String dirForMatch = effectiveWorkDir.endsWith(File.separator)
                    ? effectiveWorkDir : effectiveWorkDir + File.separator;
            String confirmDesc = "命令: " + trimmedCmd + " | 目录: " + dirForMatch;
            boolean confirmed = ToolConfirmationManager.requestConfirmation("cmd_execute", confirmDesc);
            return confirmed
                    ? SecurityCheck.allow()
                    : SecurityCheck.deny("用户拒绝执行命令：" + trimmedCmd);
        }

        if (isHighRiskCommand(trimmedCmd)) {
            if (whitelist.isWhitelisted(trimmedCmd, effectiveWorkDir)) {
                whitelist.incrementUseCount(trimmedCmd, effectiveWorkDir);
                log.info("白名单命令，跳过确认: {}", trimmedCmd);
            } else {
                String confirmDesc = "命令: " + trimmedCmd + "\n目录: " + effectiveWorkDir +
                        "\n\n确认后此命令将自动加入白名单，后续在该目录下执行时无需再次确认。";
                boolean confirmed = requestConfirmation(trimmedCmd, confirmDesc);
                if (!confirmed) {
                    return SecurityCheck.deny("用户拒绝执行该高风险命令");
                }
                String cmdPrefix = extractCommandPrefix(trimmedCmd);
                whitelist.addEntry(cmdPrefix, effectiveWorkDir);
                log.info("已自动加入白名单: [{}] @ {}", cmdPrefix, effectiveWorkDir);
            }
        }
        return SecurityCheck.allow();
    }

    /**
     * 检测命令中是否包含被禁止的文件操作
     *
     * @return 被拦截的命令名，null 表示允许
     */
    private static String findBlockedFileOperation(String command) {
        // 按管道/分隔符切分，逐段检查
        for (String segment : command.split("[|;&\n]")) {
            String[] parts = segment.trim().split("\\s+");
            if (parts.length == 0) continue;

            String cmd = extractCmdName(parts[0]);
            if (BLOCKED_FILE_COMMANDS.contains(cmd.toLowerCase())) {
                return cmd;
            }
        }
        return null;
    }

    /**
     * 判断是否为高风险命令
     */
    private static boolean isHighRiskCommand(String command) {
        for (String segment : command.split("[|;&\n]")) {
            String[] parts = segment.trim().split("\\s+");
            if (parts.length == 0) continue;

            String cmd = extractCmdName(parts[0]);
            if (HIGH_RISK_PREFIXES.contains(cmd.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从完整路径或命令词中提取命令名（去掉路径前缀）
     */
    private static String extractCmdName(String word) {
        int slash = Math.max(word.lastIndexOf('/'), word.lastIndexOf('\\'));
        return slash >= 0 ? word.substring(slash + 1) : word;
    }

    /**
     * 提取命令前缀用于白名单匹配（取前两个词）
     */
    private static String extractCommandPrefix(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length <= 2) return command.trim();
        return parts[0] + " " + parts[1];
    }

    /**
     * 解析工作目录，返回绝对路径字符串；目录无效则返回 null
     */
    private static String resolveWorkDir(String workDir) {
        if (workDir == null || workDir.isBlank()) {
            return System.getProperty("user.home");
        }
        Path path = Path.of(workDir.trim());
        if (!Files.isDirectory(path)) return null;
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * 高风险命令确认对话框（阻塞，最多等待 60 秒）
     *
     * <p>通过 {@link UserInteractionPort} 发起确认；未注入端口时拒绝执行。</p>
     */
    private static boolean requestConfirmation(String command, String description) {
        UserInteractionPort port = ToolConfirmationManager.getPort();
        if (port == null || !port.isAvailable()) {
            log.warn("UserInteractionPort 未就绪，拒绝高风险命令: {}", command);
            return false;
        }
        return port.confirm(new ConfirmRequest(
                "cmd_execute",
                "高风险",
                "即将执行高风险命令：\n\n" + description,
                ConfirmKind.CONFIRM,
                60,
                "",
                ToolConfirmationManager.isInManagedTask()));
    }

    /**
     * 实际执行 Shell 命令
     */
    private String doExecute(String command, String workDir) {
        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("/bin/sh", "-c", command);
            }

            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            // 关键：把子进程 stdin 接到空设备，使其立即读到 EOF。
            // 否则像 mvn（JLine/jansi 会读 stdin 做终端探测）这类命令，在无 TTY、且 stdin 管道
            // 一直开着不关闭时，会打印完输出后卡在读 stdin 上不退出（实测可挂数分钟）。
            File nullFile = new File(os.contains("win") ? "NUL" : "/dev/null");
            pb.redirectInput(ProcessBuilder.Redirect.from(nullFile));

            Process process = pb.start();

            // 后台线程抽干 stdout：避免旧写法"先把流读到 EOF 再 waitFor"——命令一旦挂起且
            // stdout 不关闭，readLine 会无限阻塞，导致 EXEC_TIMEOUT_SECONDS 这道 60s 上限永远到不了。
            final Process proc = process;
            // 头尾双保留：头部 HEAD_OUTPUT_LINES 行 + 尾部 TAIL_OUTPUT_LINES 行环形缓冲。
            // 旧实现保头弃尾，Maven/Gradle 的报错恰在尾部——执行体看不到错误就会换参数重跑，
            // 既丢关键信息又多烧迭代；现在错误尾部始终保住。
            final java.util.List<String> headLines = new java.util.ArrayList<>();
            final java.util.ArrayDeque<String> tailLines = new java.util.ArrayDeque<>();
            final java.util.concurrent.atomic.AtomicInteger totalLines =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            final Object outputLock = new Object();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        // 始终读到 EOF 抽干管道，防止子进程因 stdout 缓冲写满而阻塞
                        int n = totalLines.getAndIncrement();
                        synchronized (outputLock) {
                            if (n < HEAD_OUTPUT_LINES) {
                                headLines.add(line);
                            } else {
                                tailLines.addLast(line);
                                if (tailLines.size() > TAIL_OUTPUT_LINES) tailLines.removeFirst();
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被强制终止 / 流被关闭时正常抛出，忽略
                }
            }, "cmd-exec-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                // destroyForcibly 异步触发 kill；等待子进程实际退出再返回，避免留下僵尸
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                reader.interrupt();
                return ToolResponse.error("cmd_execute",
                        "命令执行超时（" + EXEC_TIMEOUT_SECONDS + " 秒），已强制终止");
            }
            // 进程已退出，等读线程把剩余输出抽完（封顶 2s，避免极端情况下卡住）
            reader.join(2000);

            int exitCode = process.exitValue();
            String result;
            synchronized (outputLock) {
                result = assembleOutput(headLines, tailLines, totalLines.get());
            }

            if (exitCode == 0) {
                return ToolResponse.success("cmd_execute",
                        result.isEmpty() ? "命令执行成功（无输出）" : result);
            } else {
                return ToolResponse.error("cmd_execute",
                        "命令退出码: " + exitCode + "\n" + (result.isEmpty() ? "（无输出）" : result));
            }

        } catch (IOException e) {
            log.error("命令执行 IO 错误: {}", command, e);
            return ToolResponse.error("cmd_execute", "执行失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResponse.error("cmd_execute", "命令执行被中断");
        }
    }

    /**
     * 组装命令输出：头尾拼接 + 中段省略标记 + 总字符上限。
     *
     * <p>字符上限尾部偏置（约 3/8 头 + 5/8 尾）：构建工具的错误信息在尾部，宁可多砍头部噪音。</p>
     */
    private static String assembleOutput(List<String> headLines,
                                         java.util.Deque<String> tailLines, int total) {
        StringBuilder sb = new StringBuilder();
        for (String l : headLines) sb.append(l).append('\n');
        int omitted = total - headLines.size() - tailLines.size();
        if (omitted > 0) {
            sb.append("\n... (中段省略 ").append(omitted).append(" 行，全文共 ").append(total)
                    .append(" 行) ...\n\n");
        }
        for (String l : tailLines) sb.append(l).append('\n');
        String result = sb.toString().trim();
        if (result.length() > MAX_OUTPUT_CHARS) {
            int headChars = MAX_OUTPUT_CHARS * 3 / 8;
            int tailChars = MAX_OUTPUT_CHARS - headChars;
            result = result.substring(0, headChars)
                    + "\n... (超长输出截断，省略 " + (result.length() - MAX_OUTPUT_CHARS)
                    + " 字符) ...\n"
                    + result.substring(result.length() - tailChars);
        }
        return result;
    }
}
