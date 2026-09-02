package com.javaclaw.api;

/** PermissionProfile revision diff 中的稳定配置分区。 */
public enum PermissionSection {
    /** 文件读取、写入、删除和符号链接。 */
    FILE,
    /** Network Broker 主机、端口和 TLS。 */
    NETWORK,
    /** 进程、PTY 和运行时限。 */
    PROCESS,
    /** 工具名称、风险和审批。 */
    TOOL,
    /** 内存、输出、子进程和文件句柄上限。 */
    RESOURCE
}
