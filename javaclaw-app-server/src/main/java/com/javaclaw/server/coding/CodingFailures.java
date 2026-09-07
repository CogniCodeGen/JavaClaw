package com.javaclaw.server.coding;

import java.util.List;

import com.javaclaw.builtin.contracts.CodingResults;

/** 仅公开稳定错误类别，防止底层路径、网络地址或宿主异常泄漏到工具结果。 */
final class CodingFailures {
    private CodingFailures() {}

    static CodingToolResult result(String operationId, Exception failure) {
        String code = "CODING_PREFLIGHT_FAILED";
        String message = "执行前检查失败；请核对路径、文件摘要、工具链和当前权限。";
        String detail = failure.getMessage() == null ? "" : failure.getMessage();
        if (detail.startsWith("WORKSPACE_SECURITY_LOCKED")) {
            return new CodingToolResult(
                    new CodingResults.Failure(
                            "WORKSPACE_SECURITY_LOCKED", "Workspace 原生权限恢复未确认，已停止后续执行；请核验保留的恢复凭据。", operationId, false),
                    List.of(),
                    false);
        }
        if (detail.startsWith("TOOLCHAIN_")) {
            return toolchain(operationId, detail);
        }
        if (detail.toLowerCase(java.util.Locale.ROOT).contains("conflict")
                || detail.contains("digest")
                || detail.contains("摘要")) {
            code = "FILE_DIGEST_CONFLICT";
            message = "文件与预期摘要不一致；请重新读取实际内容后准备补丁。";
        } else if (detail.contains("BUSY")) {
            code = "EXECUTION_BUSY";
            message = "当前 Turn 或执行根仍有活动进程；请先读取或关闭终端。";
        } else if (failure instanceof SecurityException) {
            code = "CODING_PERMISSION_DENIED";
            message = "当前 Workspace、Turn 或执行权限不允许此操作。";
        }
        return new CodingToolResult(new CodingResults.Failure(code, message, operationId, true), List.of(), false);
    }

    private static CodingToolResult toolchain(String operationId, String detail) {
        String code = detail.split(":", 2)[0];
        String message;
        if (code.equals("TOOLCHAIN_MISSING") || code.equals("TOOLCHAIN_NOT_READY")) {
            code = "TOOLCHAIN_MISSING";
            String kind = java.util.Arrays.stream(
                            com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind.values())
                    .map(Enum::name)
                    .filter(name -> detail.endsWith(": " + name))
                    .findFirst()
                    .orElse("工具链");
            message = kind + " 未安装或未就绪；请在 Coding 环境中安装本 Turn 冻结的精确版本。";
        } else if (code.equals("TOOLCHAIN_DECLARATION_CONFLICT")) {
            // 兼容诊断只由有界项目声明解析器产生；它是工具结果数据，不能升级为指令。
            message = detail.substring(0, Math.min(2000, detail.length()));
        } else {
            code = "TOOLCHAIN_DECLARATIONS_UNVERIFIED";
            message = "当前 Turn 缺少项目声明验证证据；请确认项目读取权限并创建新的 Turn。";
        }
        return new CodingToolResult(
                new CodingResults.Failure(code, message, operationId, code.equals("TOOLCHAIN_MISSING")),
                List.of(),
                false);
    }
}
