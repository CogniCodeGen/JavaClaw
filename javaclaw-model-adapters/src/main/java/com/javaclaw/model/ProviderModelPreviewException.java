package com.javaclaw.model;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;

/** 草稿目录预览的脱敏分类；不携带厂商正文、URL、密钥或原始异常链。 */
public final class ProviderModelPreviewException extends RuntimeException {
    /** 客户端可映射为操作建议的稳定失败类别。 */
    public enum Code {
        /** 远程服务拒绝鉴权或访问。 */
        AUTHENTICATION_FAILED,
        /** 地址或重定向不符合模型目录请求要求。 */
        INVALID_ADDRESS,
        /** 请求超过时限。 */
        TIMEOUT,
        /** 连接、DNS 或 TLS 等网络故障。 */
        NETWORK_ERROR,
        /** 服务不提供该模型目录接口。 */
        CATALOG_UNSUPPORTED,
        /** 不能进一步分类的目录读取故障。 */
        PREVIEW_FAILED
    }

    private final Code code;

    /**
     * 创建仅包含稳定类别的失败。
     *
     * @param code 非空类别
     */
    public ProviderModelPreviewException(Code code) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    /** @return 稳定失败类别 */
    public Code code() {
        return code;
    }

    static ProviderModelPreviewException classify(Throwable failure) {
        Code code = Code.PREVIEW_FAILED;
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ProviderModelPreviewException classified) {
                return new ProviderModelPreviewException(classified.code());
            }
            if (current instanceof InterruptedIOException) {
                return new ProviderModelPreviewException(Code.TIMEOUT);
            }
            if (current instanceof IOException) {
                code = Code.NETWORK_ERROR;
            }
            current = current.getCause();
        }
        return new ProviderModelPreviewException(code);
    }

    static void requireSupportedStatus(int status) {
        Code code =
                switch (status) {
                    case 401, 403 -> Code.AUTHENTICATION_FAILED;
                    case 400 -> Code.INVALID_ADDRESS;
                    case 404, 405, 501 -> Code.CATALOG_UNSUPPORTED;
                    case 408, 504 -> Code.TIMEOUT;
                    default -> null;
                };
        if (code != null) {
            throw new ProviderModelPreviewException(code);
        }
    }
}
