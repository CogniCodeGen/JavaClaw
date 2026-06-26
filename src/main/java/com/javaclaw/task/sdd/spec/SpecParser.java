package com.javaclaw.task.sdd.spec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * markdown → 类型化模型解析器（{@link SpecRenderer} 的逆向）。
 *
 * <p>容错优先：格式略有出入时尽量恢复而非抛错（markdown 由 LLM 产出，不可能 100% 规整）。
 * 解析失败的片段降级为空/freeform，绝不抛异常中断主流程。所有方法对 null/空白输入返回
 * 安全空值。</p>
 *
 * @author JavaClaw
 */
public final class SpecParser {

    // - [ ] 3. 动作……  /  - [x] 3. 动作……
    private static final Pattern TASK_LINE =
            Pattern.compile("^\\s*[-*]\\s*\\[([ xX])\\]\\s*(\\d+)[.、]?\\s*(.*)$");
    // 行尾的（文件, 文件）分组
    private static final Pattern TRAILING_PAREN =
            Pattern.compile("^(.*?)（([^）]*)）\\s*$");
    // 内联谓词 [type] predicate
    private static final Pattern CRITERION_INLINE =
            Pattern.compile("^\\s*\\[([^\\]]*)\\]\\s*(.*)$", Pattern.DOTALL);

    private SpecParser() {}

    // ==================== proposal.md ====================

    public static Proposal parseProposal(String md) {
        if (md == null || md.isBlank()) return new Proposal("", "", "");
        String why = section(md, "为什么");
        String what = section(md, "改什么");
        String out = section(md, "不改什么");
        if ("_（无）_".equals(out.trim())) out = "";
        return new Proposal(why, what, out);
    }

    // ==================== spec.md ====================

    public static Capability parseCapabilitySpec(String md, String fallbackName) {
        if (md == null || md.isBlank()) return new Capability(fallbackName, List.of());
        String[] lines = md.split("\n", -1);
        String capName = fallbackName;
        List<Requirement> reqs = new ArrayList<>();

        String curReqTitle = null;
        List<Scenario> curScenarios = new ArrayList<>();
        String scTitle = null, given = "", when = "", then = "";
        Criterion crit = null;
        boolean inScenario = false;

        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("# 能力：")) {
                capName = line.substring("# 能力：".length()).trim();
            } else if (line.startsWith("## 需求：")) {
                flushScenario(curScenarios, scTitle, given, when, then, crit, inScenario);
                inScenario = false; scTitle = null; given = when = then = ""; crit = null;
                if (curReqTitle != null) reqs.add(new Requirement(curReqTitle, new ArrayList<>(curScenarios)));
                curScenarios.clear();
                curReqTitle = line.substring("## 需求：".length()).trim();
            } else if (line.startsWith("### 场景：")) {
                flushScenario(curScenarios, scTitle, given, when, then, crit, inScenario);
                scTitle = line.substring("### 场景：".length()).trim();
                given = when = then = ""; crit = null; inScenario = true;
            } else if (inScenario) {
                String v = stripBulletBold(line);
                if (startsWithCi(line, "Given")) given = afterKeyword(v, "Given");
                else if (startsWithCi(line, "When") || v.startsWith("When")) when = afterKeyword(v, "When");
                else if (startsWithCi(line, "Then") || v.startsWith("Then")) then = afterKeyword(v, "Then");
                else if (line.contains("判据")) crit = parseCriterion(afterColon(line));
            }
        }
        flushScenario(curScenarios, scTitle, given, when, then, crit, inScenario);
        if (curReqTitle != null) reqs.add(new Requirement(curReqTitle, new ArrayList<>(curScenarios)));
        return new Capability(capName, reqs);
    }

    private static void flushScenario(List<Scenario> out, String title, String given, String when,
                                      String then, Criterion crit, boolean active) {
        if (active && title != null) {
            out.add(new Scenario(title, given, when, then,
                    crit == null ? Criterion.freeform(then) : crit));
        }
    }

    // ==================== tasks.md ====================

    public static List<TaskItem> parseTasks(String md) {
        List<TaskItem> out = new ArrayList<>();
        if (md == null || md.isBlank()) return out;
        for (String raw : md.split("\n", -1)) {
            Matcher m = TASK_LINE.matcher(raw);
            if (!m.find()) continue;
            boolean done = !m.group(1).isBlank();
            int idx = Integer.parseInt(m.group(2));
            String body = m.group(3).trim();

            String criterion = null;
            // 只在带冒号的"判据："/"判据:"标记处切分，避免动作文本里含"判据"二字被误切
            int cpos = body.indexOf("判据：");
            if (cpos < 0) cpos = body.indexOf("判据:");
            if (cpos >= 0) {
                criterion = afterColon(body.substring(cpos)).trim();
                body = body.substring(0, cpos).replaceAll("[\\s—–-]+$", "").trim();
            }
            List<String> files = List.of();
            Matcher pm = TRAILING_PAREN.matcher(body);
            if (pm.matches()) {
                body = pm.group(1).trim();
                String inside = pm.group(2).trim();
                if (!inside.isBlank()) {
                    files = Arrays.stream(inside.split("[,，]"))
                            .map(String::trim).filter(s -> !s.isBlank()).toList();
                }
            }
            out.add(new TaskItem(idx, body, files, criterion, done));
        }
        return out;
    }

    // ==================== criterion ====================

    public static Criterion parseCriterion(String s) {
        if (s == null || s.isBlank()) return Criterion.freeform("");
        Matcher m = CRITERION_INLINE.matcher(s.trim());
        if (m.matches()) {
            String type = m.group(1).trim();
            String pred = m.group(2).trim();
            return new Criterion(type.isBlank() ? Criterion.FREEFORM : type, pred);
        }
        return Criterion.freeform(s.trim());
    }

    // ==================== helpers ====================

    /** 抽取 {@code ## {name}} 标题下、到下一个 {@code ##}/{@code #} 之前的正文。 */
    private static String section(String md, String name) {
        String[] lines = md.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("#")) {
                if (in) break;
                String h = line.replaceFirst("^#+\\s*", "");
                if (h.equals(name) || h.startsWith(name)) { in = true; continue; }
            } else if (in) {
                sb.append(raw).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static String stripBulletBold(String line) {
        return line.replaceFirst("^[-*]\\s*", "").replace("**", "").trim();
    }

    private static boolean startsWithCi(String line, String kw) {
        String v = stripBulletBold(line);
        return v.regionMatches(true, 0, kw, 0, kw.length());
    }

    private static String afterKeyword(String v, String kw) {
        if (v.regionMatches(true, 0, kw, 0, kw.length())) {
            return v.substring(kw.length()).replaceFirst("^[:：]?\\s*", "").trim();
        }
        return v.trim();
    }

    /** 取 {@code 键：值} / {@code 键:值} 冒号后的内容；无冒号则原样返回去前缀。 */
    private static String afterColon(String s) {
        int i = s.indexOf('：');
        if (i < 0) i = s.indexOf(':');
        return i < 0 ? s.trim() : s.substring(i + 1).trim();
    }
}
