package com.javaclaw.desktop.settings;

/** 草稿目录错误的业务提示；只投影稳定分类，不猜测厂商响应或泄露秘密。 */
final class ProviderPreviewMessages {
    private ProviderPreviewMessages() {}

    static String describe(Throwable failure) {
        if (SettingsFailures.revisionConflict(failure)) {
            return "服务配置已更新，请重新读取并核对后获取模型。已选模型仍保留。";
        }
        String code = SettingsFailures.message(failure);
        return switch (code) {
            case "AUTHENTICATION_FAILED" -> "鉴权未通过，请在连接设置中核对 API Key 和该密钥的目录访问权限。";
            case "INVALID_ADDRESS" -> "服务地址不接受模型目录请求，请核对 API 根地址和协议；目录读取不跟随重定向。";
            case "TIMEOUT" -> "读取目录超时，可以重试，或直接手动添加模型。";
            case "NETWORK_ERROR" -> "无法连接模型服务，请检查地址和网络后重试，也可以手动添加模型。";
            case "CATALOG_UNSUPPORTED" -> "此服务不提供兼容的模型目录，请手动输入服务商提供的模型 ID。";
            case "PREVIEW_FAILED" -> "模型目录暂时无法读取，可以重试或手动添加模型。";
            default -> "模型目录暂时无法读取，可以重试或手动添加模型。";
        };
    }
}
