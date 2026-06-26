package com.javaclaw.task.sdd.spec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * OpenSpec 真相层的读写入口 —— SDD 架构里唯一的状态持久化通道。
 *
 * <p>所有任务状态都以 markdown 文件形式落在 {@code {workDir}/.agent/openspec/} 下
 * （布局见 {@link SpecPaths}）。本类负责：把类型化模型渲染落盘、把 markdown 折叠回
 * {@link OpenSpecChange} 派生视图、对 {@code tasks.md} 做最小化的勾选回写、变更归档。</p>
 *
 * <p>写入采用 temp-file-then-rename 原子语义；任何 IO 异常仅记日志、返回失败标志，
 * 不抛出——真相层故障不应让任务引擎崩溃。{@code workDir} 未设置时所有写操作 no-op、
 * 读操作返回空。</p>
 *
 * <p>线程模型：调用方（编排器）保证对同一 change 的写为单写者；本类不内置锁。</p>
 *
 * @author JavaClaw
 */
public final class SpecStore {

    private static final Logger log = LoggerFactory.getLogger(SpecStore.class);

    // 勾选回写：匹配某编号的实现项行，捕获复选框内字符位置
    private static final Pattern TASK_CHECKBOX =
            Pattern.compile("^(\\s*[-*]\\s*\\[)([ xX])(\\]\\s*(\\d+)[.、].*)$");

    private final String workDir;

    public SpecStore(String workDir) {
        this.workDir = workDir;
    }

    public boolean available() {
        return SpecPaths.openspecRoot(workDir) != null;
    }

    // ==================== 写入 ====================

    /** 写 proposal.md。 */
    public boolean writeProposal(String slug, String title, Proposal proposal) {
        return atomicWrite(SpecPaths.proposalFile(workDir, slug),
                SpecRenderer.renderProposal(title, proposal));
    }

    /** 写 design.md（markdown 原文；null/空白时跳过）。 */
    public boolean writeDesign(String slug, String designMd) {
        if (designMd == null || designMd.isBlank()) return false;
        return atomicWrite(SpecPaths.designFile(workDir, slug), designMd);
    }

    /** 写 tasks.md（覆盖；每次重新拆解都重写）。 */
    public boolean writeTasks(String slug, List<TaskItem> tasks) {
        return atomicWrite(SpecPaths.tasksFile(workDir, slug), SpecRenderer.renderTasks(tasks));
    }

    /** 写各能力的 changes/{slug}/specs/{能力}/spec.md。 */
    public boolean writeCapabilitySpecs(String slug, List<Capability> capabilities) {
        if (capabilities == null) return false;
        boolean ok = true;
        for (Capability cap : capabilities) {
            Path f = SpecPaths.changeSpecFile(workDir, slug, cap.name());
            ok &= atomicWrite(f, SpecRenderer.renderCapabilitySpec(cap));
        }
        return ok;
    }

    // ==================== 读取（折叠为派生视图） ====================

    /**
     * 读出整个变更并折叠为 {@link OpenSpecChange}。任一文件缺失则对应字段为空，
     * 不影响其余部分（支持半成品 change：只有 proposal、尚无 tasks 等）。
     */
    public OpenSpecChange readChange(String slug, String id, String title) {
        Proposal proposal = null;
        String proposalMd = read(SpecPaths.proposalFile(workDir, slug));
        if (proposalMd != null) proposal = SpecParser.parseProposal(proposalMd);

        String design = read(SpecPaths.designFile(workDir, slug));

        List<TaskItem> tasks = SpecParser.parseTasks(read(SpecPaths.tasksFile(workDir, slug)));

        List<Capability> capabilities = readChangeCapabilities(slug);

        return new OpenSpecChange(id, slug, title, proposal, capabilities, design, tasks);
    }

    private List<Capability> readChangeCapabilities(String slug) {
        List<Capability> out = new ArrayList<>();
        Path changeDir = SpecPaths.changeDir(workDir, slug);
        if (changeDir == null) return out;
        Path specsDir = changeDir.resolve(SpecPaths.SPECS_DIR);
        if (!Files.isDirectory(specsDir)) return out;
        try (Stream<Path> dirs = Files.list(specsDir)) {
            dirs.filter(Files::isDirectory).sorted().forEach(capDir -> {
                String name = capDir.getFileName().toString();
                String md = read(capDir.resolve(SpecPaths.SPEC_FILE));
                if (md != null) out.add(SpecParser.parseCapabilitySpec(md, name));
            });
        } catch (IOException e) {
            log.warn("[Spec] 列举能力规格失败 slug={}: {}", slug, e.getMessage());
        }
        return out;
    }

    /** 列出 changes/ 下所有变更 slug（目录名）。 */
    public List<String> listChangeSlugs() {
        List<String> out = new ArrayList<>();
        Path root = SpecPaths.changesRoot(workDir);
        if (root == null || !Files.isDirectory(root)) return out;
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().forEach(out::add);
        } catch (IOException e) {
            log.warn("[Spec] 列举变更失败: {}", e.getMessage());
        }
        return out;
    }

    public boolean changeExists(String slug) {
        Path d = SpecPaths.changeDir(workDir, slug);
        return d != null && Files.isDirectory(d);
    }

    // ==================== tasks.md 勾选回写 ====================

    /**
     * 把指定编号的实现项勾选为完成（{@code [ ]} → {@code [x]}），原子写回 tasks.md。
     *
     * @return true=成功改写；false=workDir 未设置 / 文件缺失 / 未找到该编号 / 已勾选 / IO 失败
     */
    public boolean checkTask(String slug, int index) {
        Path f = SpecPaths.tasksFile(workDir, slug);
        String md = read(f);
        if (md == null) return false;
        String[] lines = md.split("\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TASK_CHECKBOX.matcher(lines[i]);
            if (m.matches() && Integer.parseInt(m.group(4)) == index) {
                if (m.group(2).isBlank()) {                 // 仅当当前未勾选才改
                    lines[i] = m.group(1) + "x" + m.group(3);
                    changed = true;
                }
                break;
            }
        }
        if (!changed) return false;
        return atomicWrite(f, String.join("\n", lines));
    }

    /**
     * 追加新实现项到 tasks.md 末尾（重规划补做：验收未过时补的修复项）。编号自动续接现有最大编号。
     *
     * @param actions 新增项的动作描述列表（按序）
     * @return true=成功追加
     */
    public boolean appendTasks(String slug, List<String> actions) {
        if (actions == null || actions.isEmpty()) return false;
        OpenSpecChange change = readChange(slug, null, null);
        List<TaskItem> merged = new ArrayList<>(change.tasks());
        int next = merged.stream().mapToInt(TaskItem::index).max().orElse(0) + 1;
        for (String action : actions) {
            merged.add(new TaskItem(next++, action, List.of(), null, false));
        }
        return writeTasks(slug, merged);
    }

    /**
     * 懒拆解：把某个过大的实现项<b>就地替换</b>为若干子项，并对全表重新编号（保留各项原有
     * 勾选态/动作/文件/判据，只换编号）。子项插在原位置、均为未勾选。
     *
     * <p>替换后 {@code nextPendingTask()} 会自然落到第一个子项，执行循环无需额外感知拆解。
     * 这是"深度由真实难度长出来"的落地：不预测规模，遇到吃不下才裂。</p>
     *
     * @param parentIndex  待拆解项的当前编号
     * @param childActions 子项动作描述（按序，至少一个）
     * @return true=成功替换；false=未找到该编号 / 子项为空 / 写入失败
     */
    public boolean splitTask(String slug, int parentIndex, List<String> childActions) {
        if (childActions == null || childActions.isEmpty()) return false;
        OpenSpecChange change = readChange(slug, null, null);
        List<TaskItem> old = change.tasks();
        if (old.stream().noneMatch(t -> t.index() == parentIndex)) return false;

        List<TaskItem> rebuilt = new ArrayList<>();
        int n = 1;
        for (TaskItem t : old) {
            if (t.index() == parentIndex) {
                for (String action : childActions) {
                    rebuilt.add(new TaskItem(n++, action, List.of(), null, false));
                }
            } else {
                rebuilt.add(new TaskItem(n++, t.action(), t.files(), t.criterion(), t.done()));
            }
        }
        return writeTasks(slug, rebuilt);
    }

    // ==================== 归档 ====================

    /**
     * 变更通过验收后归档：把 changes/{slug}/specs/* 合并进顶层 specs/*（更新"当前真相"），
     * 并在 proposal.md 末尾追加完成标注。保留变更目录作为历史审计。
     *
     * @param completionStamp 完成时间戳文本（由调用方提供，本层不依赖时钟）
     * @return true=归档成功
     */
    public boolean archive(String slug, String completionStamp) {
        boolean ok = true;
        for (Capability cap : readChangeCapabilities(slug)) {
            Path target = SpecPaths.archivedSpecFile(workDir, cap.name());
            ok &= atomicWrite(target, SpecRenderer.renderCapabilitySpec(cap));
        }
        Path proposal = SpecPaths.proposalFile(workDir, slug);
        String md = read(proposal);
        if (md != null) {
            ok &= atomicWrite(proposal,
                    md + "\n\n---\n\n> **已完成**：" + (completionStamp == null ? "" : completionStamp) + "\n");
        }
        return ok;
    }

    // ==================== 原子写 / 读 ====================

    private boolean atomicWrite(Path target, String content) {
        if (target == null) return false;
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("[Spec] 已写入 {} ({} 字节)", target, content.length());
            return true;
        } catch (IOException e) {
            log.warn("[Spec] 写入 {} 失败（不影响主流程）: {}", target, e.getMessage());
            return false;
        }
    }

    private String read(Path p) {
        if (p == null || !Files.isRegularFile(p)) return null;
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("[Spec] 读取 {} 失败: {}", p, e.getMessage());
            return null;
        }
    }
}
