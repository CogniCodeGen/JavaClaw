package com.javaclaw.devtools;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.task.sdd.run.SddManagedTask;
import com.javaclaw.task.sdd.run.SddTaskListener;
import com.javaclaw.task.sdd.run.SddTaskManager;
import com.javaclaw.task.sdd.run.SddTaskState;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * SDD 托管任务无头驱动 —— 仅用于本地端到端验证/抓 bug，不参与正式构建逻辑。
 *
 * <p>按 {@code JavaClawApp.start()} 的同样顺序装配基础设施，然后用 {@link SddTaskManager}
 * 跑一个自包含任务，盯终态与日志找 bug。评审闸门与工具确认全部自动放行（无头）。</p>
 *
 * <p>可调系统属性：{@code -Dsdd.workdir}、{@code -Dsdd.budget}、{@code -Dsdd.timeout.min}、
 * {@code -Dsdd.title}；任务描述由命令行首个参数传入（留空用内置 FizzBuzz）。</p>
 */
public final class SddHeadlessDriver {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        UserInteractionPort port = new UserInteractionPort() {
            @Override public boolean confirm(ConfirmRequest r) {
                System.out.println(">> [确认-自动放行] " + r);
                return true;
            }
            @Override public void notify(ToastRequest r) {
                System.out.println(">> [通知] " + r);
            }
        };

        // 1. 基础设施（顺序对齐 JavaClawApp.start()）
        WorkspaceManager.getInstance().init();
        ToolConfirmationManager.setPort(port);
        PlaywrightBrowserManager browser = new PlaywrightBrowserManager(true,
                WorkspaceManager.getInstance().getCurrentBrowserDir(),
                DataManager.getInstance().getScreenshotsDir());
        AgentRuntime runtime = new AgentRuntime(browser);

        // 2. 配置 SDD 管理器（注入自动放行端口 → PortReviewGate 评审直接批准）
        SddTaskManager mgr = SddTaskManager.getInstance();
        mgr.configure(DataManager.getInstance().getDataRoot(),
                runtime.getModelFactory(), runtime.getCapabilityTools(),
                SkillManager.getInstance(), port);

        mgr.subscribe(new SddTaskListener() {
            @Override public void onTaskChanged(SddManagedTask t) {
                System.out.println(">> [状态] " + t.state + "(" + t.state.label() + ") 进度="
                        + t.progress + "% tokens=" + (t.totalInputTokens + t.totalOutputTokens));
            }
            @Override public void onLog(String id, String title, String msg) {
                System.out.println(">> [任务日志] " + msg);
            }
        });

        // 3. 创建并启动任务
        String desc = args.length > 0 ? String.join(" ", args)
                : "在工作目录下用 bash 创建脚本 fizzbuzz.sh：依次打印 1 到 15……（默认任务）";
        String workDir = System.getProperty("sdd.workdir", "/tmp/sdd-bugcheck");
        long budget = Long.getLong("sdd.budget", 500_000L);
        long timeoutMin = Long.getLong("sdd.timeout.min", 25L);
        String title = System.getProperty("sdd.title", "SDD冒烟");
        String stamp = LocalDateTime.now().format(TS);

        SddManagedTask task = mgr.create(title, desc, "auto", workDir, budget, "none", stamp);
        System.out.println("==================== 启动 ====================");
        System.out.println("任务 id=" + task.id + "  workDir=" + workDir + "  能力=auto  预算=" + budget
                + "  超时=" + timeoutMin + "分钟");
        mgr.start(task.id, stamp);

        // 4. 轮询直到终态/暂停/待人工，或超时
        long deadline = System.currentTimeMillis() + timeoutMin * 60 * 1000;
        boolean timedOut = false;
        SddTaskState st;
        do {
            Thread.sleep(3000);
            st = mgr.get(task.id).state;
            if (System.currentTimeMillis() > deadline) {
                System.out.println("!! 超时 " + timeoutMin + " 分钟，取消任务以落定状态后退出");
                // 先取消：停掉后台 runner 并把状态落为 CANCELLED，避免把半途的 RUNNING 当终态打印
                mgr.cancel(task.id);
                timedOut = true;
                break;
            }
        } while (st == SddTaskState.RUNNING || st == SddTaskState.PENDING);

        SddManagedTask fin = mgr.get(task.id);
        System.out.println("\n==================== 终态 ====================");
        System.out.println("状态  : " + fin.state + " (" + fin.state.label() + ")");
        System.out.println("进度  : " + fin.progress + "%");
        System.out.println("结果  : " + fin.result);
        System.out.println("Token : in=" + fin.totalInputTokens + " out=" + fin.totalOutputTokens
                + " 合计=" + (fin.totalInputTokens + fin.totalOutputTokens));
        System.out.println("分阶段输入: " + fin.phaseInputTokens);
        System.out.println("分阶段输出: " + fin.phaseOutputTokens);
        System.out.println("产物目录: " + fin.workDir);
        System.out.println("=============================================");

        try { runtime.shutdown(); } catch (Exception ignore) {}

        // 退出码反映真实结果：COMPLETED → 0；超时 / FAILED / NEEDS_HUMAN / CANCELLED → 非零，
        // 便于外层脚本/CI 用 $? 判定本次 e2e 验证是否通过
        int exitCode = (!timedOut && fin.state == SddTaskState.COMPLETED) ? 0 : 1;
        System.exit(exitCode);
    }
}
