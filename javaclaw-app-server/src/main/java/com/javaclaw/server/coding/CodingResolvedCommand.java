package com.javaclaw.server.coding;

import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess;

/** 平台解析后持有的命令与资源租约；仅进程所有者在完成清理后关闭，不由模型构造。 */
interface CodingResolvedCommand extends AutoCloseable {
    /** 返回已冻结实际 argv、cwd、环境、stdin 与执行时限的命令。 */
    SandboxCommand command();

    /** 返回映射实际可执行文件名且未扩大项目范围的有效权限。 */
    PermissionProfile permission();

    /** 返回仅由平台解析并拥有的运行文件、缓存与 stdin 限额。 */
    SandboxRuntimeAccess access();

    /** 返回 stdout/stderr 的固定解码编码；原始字节仍独立保留。 */
    default String outputEncoding() {
        return "UTF-8";
    }

    /** 返回 Maven 专属启动证据；其他执行类型为空。 */
    default Optional<MavenProjectLaunch.Evidence> maven() {
        return Optional.empty();
    }

    /** 返回具体解析器的启动证据；不接收子进程输出作为可信控制状态。 */
    default Optional<CanonicalPayload> evidence() {
        return Optional.empty();
    }

    /** 仅在原生进程和输出清理完成后释放解析器拥有的租约。 */
    @Override
    void close() throws Exception;
}
