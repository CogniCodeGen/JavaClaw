package com.javaclaw.task.sdd.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.spec.SpecPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 验收证据缓存 —— 把"已通过场景"连同当时的工作目录源码指纹落盘到
 * {@code {workDir}/.agent/openspec/changes/{slug}/verify-cache.json}。
 *
 * <p>动机：原先每轮补做、每次 resume 都对全部场景从头重验（每个 freeform 场景一次带文件重读的
 * critic 模型调用），是 token 的头号浪费。本缓存让"源码未变 + 上次已通过"的场景直接复用结论、
 * 零模型/命令开销；任何源码变动都会使指纹失配、整体作废、强制重验——既省 token 又不放过回归。</p>
 *
 * <p>只缓存<b>通过</b>的场景；失败场景永远重验。</p>
 *
 * @author JavaClaw
 */
public final class VerifyCache {

    private static final Logger log = LoggerFactory.getLogger(VerifyCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 指纹计算时跳过的目录（构建产物 / 版本控制 / spec 自身，避免自指与噪声）。 */
    private static final String[] SKIP_DIRS = {
            "/.agent/", "/target/", "/build/", "/.git/", "/node_modules/", "/.gradle/", "/dist/", "/out/"};

    private final Path workDir;
    private final Path cacheFile;
    private String fingerprint = "";
    private Map<String, String> passes = new LinkedHashMap<>();

    private VerifyCache(Path workDir, Path cacheFile) {
        this.workDir = workDir;
        this.cacheFile = cacheFile;
    }

    /** 加载（或新建）某变更的验收缓存。workDir/slug 无效时返回一个不落盘的空缓存。 */
    @SuppressWarnings("unchecked")
    public static VerifyCache load(String workDir, String slug) {
        Path dir = SpecPaths.changeDir(workDir, slug);
        Path wd = (workDir == null || workDir.isBlank()) ? null : Path.of(workDir).toAbsolutePath();
        if (dir == null || wd == null) return new VerifyCache(wd, null);
        VerifyCache c = new VerifyCache(wd, dir.resolve("verify-cache.json"));
        try {
            if (Files.exists(c.cacheFile)) {
                Map<String, Object> raw = MAPPER.readValue(c.cacheFile.toFile(), Map.class);
                Object fp = raw.get("fingerprint");
                Object ps = raw.get("passes");
                c.fingerprint = fp == null ? "" : fp.toString();
                if (ps instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> c.passes.put(String.valueOf(k), String.valueOf(v)));
                }
            }
        } catch (Exception e) {
            log.debug("[VerifyCache] 读取缓存失败（忽略，按空缓存处理）：{}", e.getMessage());
        }
        return c;
    }

    /** 场景唯一键（标题 + 谓词，规避同名场景串味）。 */
    public static String key(Scenario s) {
        String pred = s.criterion() == null ? "" : (s.criterion().predicate() == null ? "" : s.criterion().predicate());
        return s.title() + "|" + pred;
    }

    /** 计算当前工作目录源码树指纹（相对路径 + 大小 + mtime 的 SHA-256）。失败返回随机串保证不误命中。 */
    public String fingerprint() {
        if (workDir == null || !Files.isDirectory(workDir)) return "no-workdir";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> s = Files.walk(workDir)) {
                s.filter(Files::isRegularFile)
                        .map(workDir::relativize)
                        .map(p -> "/" + p.toString().replace('\\', '/'))
                        .filter(rel -> { for (String sk : SKIP_DIRS) if (rel.contains(sk)) return false; return true; })
                        .sorted()
                        .forEach(rel -> {
                            try {
                                Path abs = workDir.resolve(rel.substring(1));
                                md.update((rel + ":" + Files.size(abs) + ":"
                                        + Files.getLastModifiedTime(abs).toMillis() + "\n")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            } catch (Exception ignore) { /* 单文件读失败忽略 */ }
                        });
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            log.debug("[VerifyCache] 计算指纹失败：{}", e.getMessage());
            return "fp-error";
        }
    }

    /** 把缓存对齐到当前指纹：指纹变了就作废已有通过记录（强制重验）。 */
    public void syncFingerprint(String currentFp) {
        if (!currentFp.equals(this.fingerprint)) {
            this.passes = new LinkedHashMap<>();
            this.fingerprint = currentFp;
        }
    }

    /** 该场景在当前指纹下是否已通过；是则返回当时的判定依据，否则 null。 */
    public String reuse(String scenarioKey) {
        return passes.get(scenarioKey);
    }

    /** 记录一个通过的场景。 */
    public void recordPass(String scenarioKey, String detail) {
        passes.put(scenarioKey, detail == null ? "" : detail);
    }

    /** 落盘。无 cacheFile（无效工作目录）时静默跳过。 */
    public void save() {
        if (cacheFile == null) return;
        try {
            Files.createDirectories(cacheFile.getParent());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fingerprint", fingerprint);
            out.put("passes", passes);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), out);
        } catch (Exception e) {
            log.debug("[VerifyCache] 写缓存失败（忽略）：{}", e.getMessage());
        }
    }
}
