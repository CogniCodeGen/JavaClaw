package com.javaclaw.util;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 模型能力的项目文件隔离策略。
 *
 * <p>普通文件能力只能访问应用启动时确定的项目根目录。配置数据库仍由各专用管理器通过
 * JDBC 访问，不向模型暴露数据库文件路径。任意 Shell、JShell、本地 MCP 和桌面自动化
 * 无法在 Java 进程内可靠证明不会越界，因此在严格模式下统一禁用。</p>
 */
public final class ProjectAccessPolicy {

    /** 可信启动器可在 JVM 启动时显式指定项目根；缺省为进程工作目录。 */
    public static final String PROJECT_ROOT_PROPERTY = "javaclaw.project.root";

    private static final Path PROJECT_ROOT = initializeProjectRoot();
    private static final Set<String> RESERVED_TOP_LEVEL_DIRS = Set.of(".git", ".hg", ".svn", ".javaclaw");

    private ProjectAccessPolicy() {}

    /** 当前唯一允许模型文件能力访问的项目根目录。 */
    public static Path projectRoot() {
        return PROJECT_ROOT;
    }

    /** 本版本强制启用，不提供给模型或工作区配置关闭的入口。 */
    public static boolean strictIsolationEnabled() {
        return true;
    }

    /**
     * 解析并校验模型提供的路径。相对路径以项目根为基准；绝对路径也必须位于项目根内。
     * 符号链接逃逸、路径穿越、无效路径均安全默认拒绝。
     */
    public static Path resolveProjectPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new SecurityException("路径不能为空");
        }
        Path supplied;
        try {
            supplied = Path.of(rawPath.strip());
        } catch (RuntimeException e) {
            throw new SecurityException("路径格式无效", e);
        }
        for (Path segment : supplied) {
            String value = segment.toString();
            if ("..".equals(value) || "~".equals(value)) {
                throw new SecurityException("禁止使用路径穿越或用户目录缩写: " + rawPath);
            }
        }
        Path resolved = (supplied.isAbsolute() ? supplied : PROJECT_ROOT.resolve(supplied))
                .toAbsolutePath().normalize();
        return requireProjectFilePath(resolved);
    }

    /** 校验已解析路径位于项目根内，并返回规范化绝对路径。 */
    public static Path requireProjectPath(Path path) {
        if (path == null) throw new SecurityException("路径不能为空");
        Path resolved = path.toAbsolutePath().normalize();
        if (!PathGuard.isInside(PROJECT_ROOT, resolved)) {
            throw new SecurityException("严格项目隔离已拒绝访问项目外路径: " + resolved);
        }
        return resolved;
    }

    /**
     * 校验可供模型普通文件工具访问的项目路径。
     *
     * <p>应用数据库、聊天/知识库/日志/浏览器状态等受管数据，以及 VCS 与 JavaClaw 私有目录，
     * 即使物理上位于项目根内也只能由对应的专用管理器访问。</p>
     */
    public static Path requireProjectFilePath(Path path) {
        Path resolved = requireProjectPath(path);
        if (isReservedProjectPath(resolved)) {
            throw new SecurityException("严格项目隔离已拒绝普通文件工具访问受管配置或私有目录: " + resolved);
        }
        return resolved;
    }

    /** 判断路径是否位于项目根；异常一律按越界处理。 */
    public static boolean isProjectPath(Path path) {
        try {
            requireProjectPath(path);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 判断路径是否既位于项目内、又可由普通文件工具访问。 */
    public static boolean isProjectFilePath(Path path) {
        try {
            requireProjectFilePath(path);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 校验浏览器 URL；只允许 HTTP(S)、about 与项目内 file URL。 */
    public static String requireSafeBrowserUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                if (uri.getRawUserInfo() != null
                        || SensitiveDataRedactor.containsLikelyCredential(uri.toString())) {
                    throw new SecurityException("浏览器 URL 不得携带用户名、密码、令牌或会话值");
                }
                return uri.toString();
            }
            if ("about".equals(scheme)) return uri.toString();
            if (!"file".equals(scheme)) {
                throw new SecurityException("浏览器只允许 HTTP(S)、about 或项目内 file URL");
            }
            Path file = Path.of(uri);
            Path safe = requireProjectFilePath(file);
            try {
                if (Files.isRegularFile(safe) && Files.size(safe) <= 4L * 1024 * 1024
                        && SensitiveDataRedactor.containsLikelyCredential(Files.readString(safe))) {
                    throw new SecurityException("本地文件可能包含凭据，已拒绝在浏览器中打开");
                }
            } catch (java.io.IOException ignored) {
                // 图片等非文本文件由浏览器自身处理；路径边界仍已完成校验。
            }
            return safe.toUri().toString();
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SecurityException("严格项目隔离已拒绝不安全的浏览器 URL", e);
        }
    }

    /** 无法可靠限制文件系统影响面的执行通道统一返回此原因。 */
    public static String unconfinedExecutionDeniedReason() {
        return "严格项目文件隔离已启用：该能力可间接访问项目外文件，已被系统禁用";
    }

    /**
     * HTTP MCP 必须是远端端点；localhost、回环地址和通配监听地址可能桥接本机文件系统，拒绝。
     */
    public static URI requireRemoteMcpEndpoint(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl == null ? "" : rawUrl.strip());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new SecurityException("MCP HTTP 端点必须是有效的 http:// 或 https:// URL");
            }
            if (uri.getRawUserInfo() != null) {
                throw new SecurityException("MCP HTTP 端点不得在 URL 中携带用户名或密码");
            }
            if (SensitiveDataRedactor.containsLikelyCredential(uri.toString())) {
                throw new SecurityException("MCP HTTP 端点不得在 URL 查询串或片段中携带凭据");
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
                normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
            }
            while (normalizedHost.endsWith(".")) {
                normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
            }
            if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")
                    || normalizedHost.equals("localhost.localdomain")
                    || normalizedHost.equals("ip6-localhost")
                    || normalizedHost.equals("ip6-loopback")
                    || normalizedHost.endsWith(".local")
                    || normalizedHost.equals("0.0.0.0") || normalizedHost.equals("::")
                    || normalizedHost.equals("::1") || normalizedHost.startsWith("127.")
                    || normalizedHost.startsWith("::ffff:127.")) {
                throw new SecurityException("严格项目隔离已拒绝本机或回环 MCP 端点");
            }
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(normalizedHost);
            } catch (UnknownHostException e) {
                throw new SecurityException("MCP HTTP 端点域名无法解析，已安全拒绝", e);
            }
            if (addresses.length == 0) {
                throw new SecurityException("MCP HTTP 端点域名未解析到可用地址");
            }
            for (InetAddress address : addresses) {
                if (isLocalOrPrivateAddress(address)) {
                    throw new SecurityException("严格项目隔离已拒绝本机、私网或链路本地 MCP 端点");
                }
            }
            return uri;
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SecurityException("MCP HTTP 端点 URL 格式不正确", e);
        }
    }

    /** 返回不含 user-info、查询串和 fragment 的端点摘要，供日志与模型可见列表使用。 */
    public static String remoteEndpointSummary(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl == null ? "" : rawUrl.strip());
            if (uri.getScheme() == null || uri.getHost() == null) return "（无效 URL）";
            StringBuilder out = new StringBuilder()
                    .append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://")
                    .append(uri.getHost());
            if (uri.getPort() >= 0) out.append(':').append(uri.getPort());
            String path = uri.getRawPath();
            if (path != null && !path.isBlank()) out.append(path);
            return out.toString();
        } catch (RuntimeException e) {
            return "（无效 URL）";
        }
    }

    private static boolean isLocalOrPrivateAddress(InetAddress address) {
        if (address == null) return true;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            // IPv6 unique-local fc00::/7。
            if ((first & 0xfe) == 0xfc) return true;
            // IPv4-mapped IPv6 地址仍按其尾部 IPv4 判断，避免 ::ffff:127.0.0.1 等表示法绕过。
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
            mapped &= (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
            if (mapped) {
                int a = bytes[12] & 0xff;
                int b = bytes[13] & 0xff;
                return isPrivateIpv4(a, b);
            }
        } else if (bytes.length == 4) {
            return isPrivateIpv4(bytes[0] & 0xff, bytes[1] & 0xff);
        }
        return false;
    }

    private static boolean isPrivateIpv4(int first, int second) {
        return first == 0 || first == 10 || first == 127
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 100 && second >= 64 && second <= 127)
                || first >= 224;
    }

    private static boolean isReservedProjectPath(Path resolved) {
        Path relative;
        try {
            relative = PROJECT_ROOT.relativize(resolved);
        } catch (IllegalArgumentException e) {
            return true;
        }
        if (relative.getNameCount() > 0
                && RESERVED_TOP_LEVEL_DIRS.contains(relative.getName(0).toString())) {
            return true;
        }

        Path defaultDataDir = PROJECT_ROOT.resolve("data").toAbsolutePath().normalize();
        if (isManagedDataPath(resolved, defaultDataDir)) return true;

        Path configuredDataDir = configuredDataDirectory();
        return !configuredDataDir.equals(defaultDataDir)
                && isManagedDataPath(resolved, configuredDataDir);
    }

    private static boolean isManagedDataPath(Path resolved, Path dataDir) {
        if (!dataDir.startsWith(PROJECT_ROOT) || !resolved.startsWith(dataDir)) return false;
        Path dataRelative = dataDir.relativize(resolved);
        // 截图是模型工具的显式产物；其余应用数据只能通过专用管理器访问。
        return dataRelative.getNameCount() == 0
                || !"screenshots".equals(dataRelative.getName(0).toString());
    }

    private static Path configuredDataDirectory() {
        String configured = System.getProperty("javaclaw.data.dir");
        Path dataDir = configured == null || configured.isBlank()
                ? PROJECT_ROOT.resolve("data") : Path.of(configured);
        return dataDir.toAbsolutePath().normalize();
    }

    private static Path initializeProjectRoot() {
        String configured = System.getProperty(PROJECT_ROOT_PROPERTY);
        String source = configured == null || configured.isBlank()
                ? System.getProperty("user.dir") : configured;
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("无法确定项目根目录");
        }
        Path root = Path.of(source).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("项目根目录不存在或不是目录: " + root);
        }
        try {
            return root.toRealPath();
        } catch (Exception e) {
            throw new IllegalStateException("无法解析项目根目录: " + root, e);
        }
    }
}
