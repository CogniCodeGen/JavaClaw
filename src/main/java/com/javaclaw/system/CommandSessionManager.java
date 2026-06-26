package com.javaclaw.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 命令行会话管理器
 *
 * <p>为命令行工具提供长生命周期的交互式 Shell 会话能力。每个会话独占一个
 * Shell 进程（Unix 用 {@code /bin/bash --norc}，Windows 用 {@code cmd.exe /q /k}），
 * stdin 可写、stdout/stderr 合并后由后台线程读入环形缓冲。</p>
 *
 * <p>典型用法：</p>
 * <ol>
 *   <li>{@link #open(String)} 创建会话，得到 sessionId</li>
 *   <li>反复调用 {@link ShellSession#sendInput} 写入命令、{@link ShellSession#waitForMarker}
 *       或 {@link ShellSession#waitForAny} 拿到输出</li>
 *   <li>{@link #close(String)} 关闭会话</li>
 * </ol>
 *
 * <p>限制：最多 {@link #MAX_SESSIONS} 个并发会话，缓冲上限 {@link #MAX_BUFFER_CHARS} 字符，
 * 空闲超过 {@link #IDLE_TIMEOUT_MS} 自动回收；JVM 退出时统一杀掉所有进程。</p>
 *
 * <p>本类不做命令安全检查（黑名单 / 高风险确认）；调用方在 send 之前自行约束。</p>
 *
 * @author JavaClaw
 */
public final class CommandSessionManager {

    private static final Logger log = LoggerFactory.getLogger(CommandSessionManager.class);

    private static final CommandSessionManager INSTANCE = new CommandSessionManager();

    public static CommandSessionManager getInstance() {
        return INSTANCE;
    }

    /** 最大并发会话数 */
    public static final int MAX_SESSIONS = 10;

    /** 单个会话缓冲上限（字符） */
    public static final int MAX_BUFFER_CHARS = 1_000_000;

    /** 空闲超时（毫秒），超过则后台线程自动回收 */
    public static final long IDLE_TIMEOUT_MS = 30L * 60 * 1000;

    private final Map<String, ShellSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor;

    private CommandSessionManager() {
        janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cmd-session-janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleAtFixedRate(this::cleanupIdle, 5, 5, TimeUnit.MINUTES);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "cmd-session-shutdown"));
    }

    /**
     * 创建新会话
     *
     * @param workDir 工作目录绝对路径
     * @return 新会话
     * @throws IOException 会话数已达上限，或进程启动失败
     */
    public ShellSession open(String workDir) throws IOException {
        if (sessions.size() >= MAX_SESSIONS) {
            throw new IOException("会话数量已达上限 " + MAX_SESSIONS + " 个，请先关闭部分会话再开新会话");
        }

        ProcessBuilder pb;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // /q 关闭命令回显，/k 保留会话不退出
            pb = new ProcessBuilder("cmd.exe", "/q", "/k");
        } else {
            // --norc 跳过 ~/.bashrc 干扰；不传 -i，避免回显输入与 PS1 提示符破坏哨兵匹配
            pb = new ProcessBuilder("/bin/bash", "--norc");
        }
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        String id = "sh-" + Long.toString(System.currentTimeMillis(), 36)
                + "-" + ThreadLocalRandom.current().nextInt(1000, 9999);
        ShellSession s = new ShellSession(id, workDir, proc);
        sessions.put(id, s);
        log.info("已创建命令行会话: {} @ {}", id, workDir);
        return s;
    }

    public ShellSession get(String id) {
        if (id == null) return null;
        return sessions.get(id);
    }

    /**
     * 关闭会话
     *
     * @return true=成功关闭并移除；false=会话不存在
     */
    public boolean close(String id) {
        ShellSession s = sessions.remove(id);
        if (s == null) return false;
        s.terminate();
        log.info("已关闭命令行会话: {}", id);
        return true;
    }

    public Collection<ShellSession> list() {
        return sessions.values();
    }

    public int count() {
        return sessions.size();
    }

    private void cleanupIdle() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> {
            ShellSession s = e.getValue();
            boolean dead = !s.isAlive();
            boolean idle = (now - s.lastActivity()) > IDLE_TIMEOUT_MS;
            if (dead || idle) {
                log.info("自动回收命令行会话: {} (alive={}, idleMs={})", e.getKey(), s.isAlive(), now - s.lastActivity());
                s.terminate();
                return true;
            }
            return false;
        });
    }

    private void shutdown() {
        janitor.shutdownNow();
        for (ShellSession s : sessions.values()) {
            try {
                s.terminate();
            } catch (Exception ignored) {}
        }
        sessions.clear();
    }

    // ==================== 内部会话对象 ====================

    /** 哨兵等待结果：body 是哨兵之前的所有输出，markerLine 是哨兵那一行（含退出码） */
    public record MarkerHit(String body, String markerLine) {}

    public static final class ShellSession {

        private final String id;
        private final String workDir;
        private final long createdAt;
        private final Process process;
        private final BufferedWriter stdin;
        private final StringBuilder buffer = new StringBuilder();
        private final Object bufferLock = new Object();
        private volatile long lastActivity;
        private final Thread readerThread;

        ShellSession(String id, String workDir, Process process) {
            this.id = id;
            this.workDir = workDir;
            this.process = process;
            this.createdAt = System.currentTimeMillis();
            this.lastActivity = createdAt;
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.readerThread = new Thread(this::pumpOutput, "cmd-session-reader-" + id);
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        public String id() { return id; }
        public String workDir() { return workDir; }
        public long createdAt() { return createdAt; }
        public long lastActivity() { return lastActivity; }
        public boolean isAlive() { return process.isAlive(); }

        public int bufferSize() {
            synchronized (bufferLock) {
                return buffer.length();
            }
        }

        /** 把当前缓冲区里所有未读输出取出并清空 */
        public String drain() {
            synchronized (bufferLock) {
                String s = buffer.toString();
                buffer.setLength(0);
                return s;
            }
        }

        /** 写入 stdin 但不附加换行；调用方自己决定何时加 \n */
        public void sendInput(String text) throws IOException {
            stdin.write(text);
            stdin.flush();
            lastActivity = System.currentTimeMillis();
        }

        /**
         * 阻塞等待哨兵字符串出现在缓冲区
         *
         * <p>命中后返回 {@link MarkerHit}（body=哨兵之前所有输出，markerLine=哨兵那一行的剩余内容），
         * 并把这两部分从缓冲区移除；超时返回 null（缓冲区保留，调用方可后续 drain 读取）。</p>
         */
        public MarkerHit waitForMarker(String marker, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (bufferLock) {
                while (true) {
                    int idx = buffer.indexOf(marker);
                    if (idx >= 0) {
                        int afterMarker = idx + marker.length();
                        int lineEnd = buffer.indexOf("\n", afterMarker);
                        int cutEnd = lineEnd >= 0 ? lineEnd + 1 : buffer.length();
                        String body = buffer.substring(0, idx);
                        String markerLine = buffer.substring(afterMarker, lineEnd >= 0 ? lineEnd : buffer.length());
                        buffer.delete(0, cutEnd);
                        lastActivity = System.currentTimeMillis();
                        return new MarkerHit(body, markerLine);
                    }
                    long now = System.currentTimeMillis();
                    if (now >= deadline) return null;
                    if (!process.isAlive() && buffer.length() == 0) return null;
                    bufferLock.wait(Math.min(deadline - now, 500));
                }
            }
        }

        /**
         * 阻塞等待任意输出出现；最多等 timeoutMs，期间一旦有新数据立即聚合后返回
         *
         * <p>当 quietMs &gt; 0 时，等到第一个字节后再等 quietMs 毫秒的静默期再一次性返回，
         * 用于聚合短时间内的多次写入（典型场景：程序写完一段 prompt 之后等待用户输入）。</p>
         */
        public String waitForAny(long timeoutMs, long quietMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (bufferLock) {
                while (buffer.length() == 0) {
                    long now = System.currentTimeMillis();
                    if (now >= deadline) return "";
                    if (!process.isAlive()) return "";
                    bufferLock.wait(Math.min(deadline - now, 500));
                }
                if (quietMs > 0) {
                    long lastSize = buffer.length();
                    long quietDeadline = System.currentTimeMillis() + quietMs;
                    while (System.currentTimeMillis() < quietDeadline) {
                        bufferLock.wait(Math.min(quietDeadline - System.currentTimeMillis(), 100));
                        if (buffer.length() != lastSize) {
                            lastSize = buffer.length();
                            quietDeadline = System.currentTimeMillis() + quietMs;
                        }
                    }
                }
                String s = buffer.toString();
                buffer.setLength(0);
                lastActivity = System.currentTimeMillis();
                return s;
            }
        }

        public void terminate() {
            try {
                stdin.close();
            } catch (IOException ignored) {}
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerThread.interrupt();
        }

        private void pumpOutput() {
            try (InputStreamReader r = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[2048];
                int n;
                while ((n = r.read(buf)) != -1) {
                    String chunk = new String(buf, 0, n);
                    synchronized (bufferLock) {
                        buffer.append(chunk);
                        if (buffer.length() > MAX_BUFFER_CHARS) {
                            int excess = buffer.length() - MAX_BUFFER_CHARS;
                            buffer.delete(0, excess);
                        }
                        bufferLock.notifyAll();
                    }
                }
            } catch (IOException e) {
                log.debug("会话读取线程结束: {}", id, e);
            } finally {
                synchronized (bufferLock) {
                    bufferLock.notifyAll();
                }
            }
        }
    }
}
