package com.javaclaw.nativehost.sandbox;

/** 把已验证命令转换为当前 OS 的强制隔离启动计划。 */
interface SandboxCommandBuilder {
    SandboxLaunchPlan build(ValidatedSandboxCommand command);

    String name();
}
