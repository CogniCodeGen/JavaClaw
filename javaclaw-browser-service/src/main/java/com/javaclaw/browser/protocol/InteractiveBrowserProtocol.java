package com.javaclaw.browser.protocol;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.BrowserContracts;

/** 常驻 Browser 的私有多路帧；单个读线程区分命令和反向网络响应，避免 Playwright actor 争读 stdin。 */
public final class InteractiveBrowserProtocol {
    /** 旧启动信封中的常驻交互入口。 */
    public static final String OPEN = "interactive";
    /** 执行页面操作。 */
    public static final String ACTION = "action";
    /** 替换控制租约。 */
    public static final String LEASE = "lease";
    /** 读取纯会话状态。 */
    public static final String STATUS = "status";
    /** 私有返回含 IndexedDB 的状态。 */
    public static final String SAVE = "save";
    /** 人工明确选中后私有捕获用户名和密码。 */
    public static final String CAPTURE = "capture";
    /** 在一个 actor 命令中使用私有字节填入凭据。 */
    public static final String FILL_CREDENTIALS = "fillCredentials";
    /** 用户显式请求时枚举不含值的登录表单。 */
    public static final String PREPARE_CREDENTIALS = "prepareCredentials";
    /** 关闭 Context 和进程。 */
    public static final String CLOSE = "close";

    private InteractiveBrowserProtocol() {}

    /**
     * @param expectedLease 宿主在提交时冻结的控制租约，不能由当前控制权替代
     * @param action 页面操作
     */
    public record ActionRequest(BrowserContracts.AccessLease expectedLease, BrowserContracts.Action action) {}

    /**
     * @param expectedLease 用户操作冻结的 HUMAN 租约
     * @param expectedOrigin 账号站点的精确 HTTPS Origin
     */
    public record FormsRequest(BrowserContracts.AccessLease expectedLease, URI expectedOrigin) {}

    /**
     * @param expectedLease 宿主冻结的控制租约
     * @param expectedOrigin 账号站点的精确 HTTPS Origin
     * @param target 同页面同表单的当前输入引用
     */
    public record CredentialsRequest(
            BrowserContracts.AccessLease expectedLease,
            URI expectedOrigin,
            BrowserContracts.CredentialsTarget target) {}

    /**
     * @param session 与请求租约一致的会话状态
     * @param forms 不含输入值的表单描述
     */
    public record FormsResult(BrowserContracts.SessionView session, List<BrowserContracts.LoginForm> forms) {
        /** 保存表单描述的不可变副本。 */
        public FormsResult {
            forms = List.copyOf(forms);
        }
    }

    /** @param origin 被阻断的精确 HTTPS Origin；不携带路径、查询参数、表单或请求正文 */
    public record OriginNotice(URI origin) {}

    /** 私有帧方向与用途。 */
    public enum Kind {
        COMMAND,
        REPLY,
        DENIED_ORIGIN,
        NETWORK,
        NETWORK_REPLY
    }

    /**
     * @param kind 用途
     * @param id 对应命令或网络序号
     * @param operation 操作名称
     * @param payload 私有元数据
     * @param binaryBytes 后续二进制长度
     * @param error 固定错误代码，不含原始异常
     */
    public record Frame(
            Kind kind, long id, String operation, CanonicalPayload payload, int binaryBytes, Optional<String> error) {
        /** 限制元数据和帧长度。 */
        public Frame {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(payload, "payload");
            error = Objects.requireNonNull(error, "error");
            if (id < 1
                    || operation.length() > 40
                    || binaryBytes < 0
                    || binaryBytes > BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES
                    || error.filter(value -> !value.matches("[A-Z_]{1,100}")).isPresent()) {
                throw new IllegalArgumentException("invalid interactive Browser frame");
            }
            if (error.isPresent() && binaryBytes != 0) {
                throw new IllegalArgumentException("failed Browser frame cannot carry binary data");
            }
        }
    }

    /**
     * @param frame 私有帧元数据
     * @param bytes 私有二进制，构造时复制；关闭清空
     */
    public record Packet(Frame frame, byte[] bytes) implements AutoCloseable {
        /** 验证元数据与二进制长度。 */
        public Packet {
            Objects.requireNonNull(frame, "frame");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (bytes.length != frame.binaryBytes()) {
                throw new IllegalArgumentException("interactive Browser binary length mismatch");
            }
        }

        /** @return 私有字节副本，调用方负责清理 */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /** 清空持有的私有二进制。 */
        @Override
        public void close() {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
