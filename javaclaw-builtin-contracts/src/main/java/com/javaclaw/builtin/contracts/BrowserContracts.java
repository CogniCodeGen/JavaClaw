package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/** Site 扩展的交互浏览器契约；凭据与二进制内容只能经过 Worker 私有帧，不进入这些可展示 DTO。 */
public final class BrowserContracts {
    /** 单会话最多保留八个标签页。 */
    public static final int MAXIMUM_TABS = 8;
    /** 单次截图、上传或下载的原始字节上限。 */
    public static final int MAXIMUM_ARTIFACT_BYTES = 4 * 1024 * 1024;

    private BrowserContracts() {}

    /** 浏览器操作权；NONE 保留页面但禁止所有网络请求。 */
    public enum ControlMode {
        NONE,
        ASSISTANT,
        HUMAN
    }

    /** 常驻浏览器会话状态。 */
    public enum SessionState {
        OPEN,
        CLOSED,
        FAILED
    }

    /** 受限操作集合；SECRET 与 CAPTURE 不应直接注册为模型工具。 */
    public enum Operation {
        SNAPSHOT,
        NAVIGATE,
        BACK,
        FORWARD,
        RELOAD,
        NEW_TAB,
        CLOSE_TAB,
        SWITCH_TAB,
        CLICK,
        DOUBLE_CLICK,
        FILL,
        SELECT,
        CHECK,
        PRESS,
        SCROLL,
        HOVER,
        WAIT,
        SCREENSHOT,
        CLICK_AT,
        DRAG,
        UPLOAD,
        DOWNLOAD_LINK,
        DOWNLOAD,
        FILL_SECRET
    }

    /**
     * @param accountId 账号标识
     * @param securityRevision 冻结的账号安全版本，从一开始
     */
    public record AccountBinding(String accountId, long securityRevision) {
        /** 校验账号安全绑定。 */
        public AccountBinding {
            accountId = text(accountId, 200, "accountId");
            if (securityRevision < 1) {
                throw new IllegalArgumentException("account security revision must be positive");
            }
        }
    }

    /**
     * @param workspaceId 唯一 Workspace
     * @param threadId 唯一 Thread
     * @param account 可选账号安全绑定
     */
    public record Owner(WorkspaceId workspaceId, ThreadId threadId, Optional<AccountBinding> account) {
        /** 冻结所有权；切换账号必须创建新的 Context。 */
        public Owner {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(threadId, "threadId");
            account = Objects.requireNonNull(account, "account");
        }
    }

    /**
     * 宿主授予的短期操作租约；持有页面不等于持续拥有网络权限。
     *
     * @param mode 当前控制者
     * @param leaseId 宿主生成的租约标识
     * @param generation 单调控制代次，改变控制权必须递增
     * @param expiresAt 到期时刻；NONE 可以使用当前时刻
     * @param allowedOrigins 精确 HTTP(S) 来源，不接受通配符、路径、用户信息
     */
    public record AccessLease(
            ControlMode mode, String leaseId, long generation, Instant expiresAt, Set<URI> allowedOrigins) {
        /** 校验租约并冻结来源；每个请求仍必须由宿主再次授权。 */
        public AccessLease {
            Objects.requireNonNull(mode, "mode");
            leaseId = text(leaseId, 200, "leaseId");
            Objects.requireNonNull(expiresAt, "expiresAt");
            allowedOrigins = Set.copyOf(allowedOrigins);
            allowedOrigins.forEach(BrowserContracts::origin);
            if (generation < 1 || allowedOrigins.size() > 128) {
                throw new IllegalArgumentException("invalid Browser lease bounds");
            }
        }

        /**
         * @param now 当前时刻
         * @return 非 NONE 且尚未到期时为真
         */
        public boolean active(Instant now) {
            return mode != ControlMode.NONE && expiresAt.isAfter(now);
        }
    }

    /**
     * @param sessionId 宿主生成的会话 UUID
     * @param owner 唯一所有者
     * @param uri 初始页面
     * @param lease 初始操作租约
     */
    public record OpenTask(String sessionId, Owner owner, URI uri, AccessLease lease) {
        /** 校验初始页面与所有权。 */
        public OpenTask {
            sessionId = uuid(sessionId);
            Objects.requireNonNull(owner, "owner");
            uri = pageUri(uri);
            Objects.requireNonNull(lease, "lease");
        }
    }

    /**
     * @param pageId 标签页标识；空值指当前页
     * @param reference 当前快照元素引用；不使用时为空
     * @param frameId 坐标帧标识；不使用时为空
     */
    public record Target(String pageId, String reference, String frameId) {
        /** 校验目标长度；不允许模型注入 CSS 或 JavaScript。 */
        public Target {
            pageId = bounded(pageId, 100, "pageId");
            reference = bounded(reference, 100, "reference");
            frameId = bounded(frameId, 100, "frameId");
        }

        /** @return 当前页且未指定元素的目标 */
        public static Target current() {
            return new Target("", "", "");
        }
    }

    /**
     * @param x 截图图片中的横向像素
     * @param y 截图图片中的纵向像素
     */
    public record Point(double x, double y) {
        /** 坐标必须有限且非负；执行时再核对帧边界。 */
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0) {
                throw new IllegalArgumentException("invalid image coordinate");
            }
        }
    }

    /**
     * @param from 拖动起点图片像素
     * @param to 拖动终点图片像素
     */
    public record Drag(Point from, Point to) {
        /** 校验完整拖动。 */
        public Drag {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /**
     * @param fileName 展示文件名，不是路径
     * @param mediaType 内容 MIME
     */
    public record FileSpec(String fileName, String mediaType) {
        /** 拒绝宿主路径和控制字符。 */
        public FileSpec {
            fileName = text(fileName, 200, "fileName");
            mediaType = text(mediaType, 100, "mediaType");
            if (fileName.contains("/")
                    || fileName.contains("\\")
                    || fileName.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Browser file name must not contain a path");
            }
        }
    }

    /**
     * @param value 普通文本参数，不得放密码
     * @param point 可选图片坐标
     * @param drag 可选拖动
     * @param file 可选上传文件元数据
     */
    public record ActionInput(String value, Optional<Point> point, Optional<Drag> drag, Optional<FileSpec> file) {
        /** 冻结有界操作输入。 */
        public ActionInput {
            value = bounded(value, 16_384, "value");
            point = Objects.requireNonNull(point, "point");
            drag = Objects.requireNonNull(drag, "drag");
            file = Objects.requireNonNull(file, "file");
        }

        /**
         * @param value 非敏感文本
         * @return 不含坐标或文件的输入
         */
        public static ActionInput text(String value) {
            return new ActionInput(value, Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /**
     * @param operation 受限动作
     * @param target 当前观察目标
     * @param input 类型化参数
     */
    public record Action(Operation operation, Target target, ActionInput input) {
        /** 校验完整动作。 */
        public Action {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(input, "input");
        }

        /**
         * @param operation 无参数动作
         * @return 当前页动作
         */
        public static Action simple(Operation operation) {
            return new Action(operation, Target.current(), ActionInput.text(""));
        }
    }

    /**
     * @param pageId 页面标识
     * @param uri 当前地址
     * @param title 有界标题
     * @param active 是否为选中标签
     */
    public record Tab(String pageId, URI uri, String title, boolean active) {}

    /**
     * @param reference 观察代次绑定的元素引用
     * @param role ARIA 或原生角色
     * @param name 可访问名称
     * @param tag 标签名
     * @param enabled 是否启用
     * @param checked 可选选中状态
     */
    public record Element(
            String reference, String role, String name, String tag, boolean enabled, Optional<Boolean> checked) {}

    /**
     * @param width CSS 视口宽度
     * @param height CSS 视口高度
     * @param scrollX 横向滚动 CSS 像素
     * @param scrollY 纵向滚动 CSS 像素
     * @param deviceScaleFactor 设备缩放
     */
    public record Viewport(int width, int height, double scrollX, double scrollY, double deviceScaleFactor) {}

    /**
     * @param frameId 图片观察标识
     * @param pageId 页面标识
     * @param documentEpoch 页面观察代次
     * @param controlGeneration 控制权代次
     * @param viewport 截图时视口
     * @param imageWidth 图片像素宽度
     * @param imageHeight 图片像素高度
     */
    public record Frame(
            String frameId,
            String pageId,
            long documentEpoch,
            long controlGeneration,
            Viewport viewport,
            int imageWidth,
            int imageHeight) {}

    /**
     * @param sessionId 会话标识
     * @param owner 固定所有者
     * @param state 生命周期状态
     * @param lease 当前控制租约
     * @param tabs 有序标签页
     */
    public record SessionView(String sessionId, Owner owner, SessionState state, AccessLease lease, List<Tab> tabs) {
        /** 防止外部修改标签列表。 */
        public SessionView {
            tabs = List.copyOf(tabs);
        }
    }

    /**
     * @param pageId 当前页
     * @param uri 当前地址
     * @param title 页面标题
     * @param text 脱敏正文
     * @param elements 有界元素引用
     * @param downloads 可下载标识
     */
    public record PageSnapshot(
            String pageId, URI uri, String title, String text, List<Element> elements, List<DownloadInfo> downloads) {
        /** 冻结页面观察。 */
        public PageSnapshot {
            elements = List.copyOf(elements);
            downloads = List.copyOf(downloads);
        }
    }

    /**
     * @param downloadId 当前会话内下载标识
     * @param fileName 安全展示名称
     */
    public record DownloadInfo(String downloadId, String fileName) {}

    /**
     * @param file 文件展示元数据
     * @param sizeBytes 原始字节数，不超过单次附件上限
     */
    public record Artifact(FileSpec file, long sizeBytes) {}

    /**
     * @param session 当前会话
     * @param page 脱敏页面快照
     * @param frame 可选截图定位帧
     * @param artifact 可选二进制附件元数据
     */
    public record Observation(
            SessionView session, PageSnapshot page, Optional<Frame> frame, Optional<Artifact> artifact) {}

    /**
     * @param pageId 用户选择的页面
     * @param usernameRef 用户名输入元素引用
     * @param passwordRef 密码输入元素引用
     */
    public record CredentialsTarget(String pageId, String usernameRef, String passwordRef) {
        /** 捕获只接受已观察引用。 */
        public CredentialsTarget {
            pageId = text(pageId, 100, "pageId");
            usernameRef = text(usernameRef, 100, "usernameRef");
            passwordRef = text(passwordRef, 100, "passwordRef");
        }
    }

    /**
     * 用户明确请求时返回的登录表单描述，所有字段都不含输入值。
     *
     * @param label 表单标签
     * @param usernameLabel 用户名字段标签
     * @param passwordLabel 密码字段标签
     * @param target 当前页面内同表单的两个引用
     */
    public record LoginForm(String label, String usernameLabel, String passwordLabel, CredentialsTarget target) {}

    /**
     * @param uri 精确 HTTP(S) 目标
     * @param method HTTP 方法
     * @param headers 私有请求头，禁止日志和模型输出
     */
    public record NetworkRequest(URI uri, String method, Map<String, List<String>> headers) {
        /** 请求仍须由宿主 Broker 校验来源、DNS、租约和敏感头。 */
        public NetworkRequest {
            uri = pageUri(uri);
            method = text(method, 20, "method").toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")
                    .contains(method)) {
                throw new IllegalArgumentException("unsupported Browser HTTP method");
            }
            if (headers.size() > 128) {
                throw new IllegalArgumentException("too many Browser headers");
            }
            headers = headers.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> text(entry.getKey(), 200, "header"), entry -> List.copyOf(entry.getValue())));
        }
    }

    private static String uuid(String value) {
        return java.util.UUID.fromString(text(value, 36, "sessionId")).toString();
    }

    private static URI pageUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.toString().length() > 8192) {
            throw new IllegalArgumentException("Browser URI must be HTTP(S) without credentials");
        }
        return uri;
    }

    private static void origin(URI uri) {
        pageUri(uri);
        if (uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Browser authorization must be an exact origin");
        }
    }

    private static String text(String value, int maximum, String name) {
        String checked = bounded(value, maximum, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }

    private static String bounded(String value, int maximum, String name) {
        if (Objects.requireNonNull(value, name).length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds limit");
        }
        return value;
    }
}
