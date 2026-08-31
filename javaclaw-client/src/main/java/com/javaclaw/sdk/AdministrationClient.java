package com.javaclaw.sdk;

import java.util.concurrent.CompletableFuture;

import com.javaclaw.sdk.model.AttachmentInfo;
import com.javaclaw.sdk.model.JsonDocument;

/** 配置与诊断领域客户端。远程结果通过 CompletableFuture 返回，RPC 错误以异常完成；查询不返回凭据明文。 */
public final class AdministrationClient {
    private final ProtocolClient protocol;
    private final SdkProtocolMapper mapper;

    AdministrationClient(ProtocolClient protocol, SdkProtocolMapper mapper) {
        this.protocol = protocol;
        this.mapper = mapper;
    }

    /** 异步读取脱敏配置视图；敏感项只返回配置状态与修订信息。 */
    public CompletableFuture<JsonDocument> readConfiguration() {
        return protocol.readConfiguration().thenApply(mapper::document);
    }

    /** 读取服务端保存的桌面主题；没有偏好时沿用原版翡翠主题，不读取旧数据目录。 */
    public CompletableFuture<String> readDesktopTheme() {
        return protocol.readConfiguration()
                .thenApply(value -> value.path("desktop.theme").asText("emerald"));
    }

    /** 保存九个原版主题之一；只修改桌面偏好，不触及模型、工具或权限配置。 */
    public CompletableFuture<Void> setDesktopTheme(String themeId) {
        if (!java.util.Set.of(
                        "emerald", "midnight", "carbon", "sapphire", "ocean", "plum", "terracotta", "honey", "graphite")
                .contains(themeId)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown desktop theme"));
        }
        return protocol.updateConfiguration(mapper.desktopTheme(themeId)).thenApply(ignored -> null);
    }

    /** 异步提交配置 merge patch；仅更新服务端允许的字段，不绕过权限上限。 */
    public CompletableFuture<JsonDocument> updateConfiguration(JsonDocument mergePatch) {
        return protocol.updateConfiguration(mapper.parse(mergePatch)).thenApply(mapper::document);
    }

    /** 异步读取最多 limit 条诊断及运行状态；错误详情由服务端脱敏。 */
    public CompletableFuture<JsonDocument> readDiagnostics(int limit) {
        return protocol.readDiagnostics(limit).thenApply(mapper::document);
    }

    /** 请求生成诊断附件并返回 SHA-256 元数据；key 用于去重，不向客户端返回服务器导出路径。 */
    public CompletableFuture<AttachmentInfo> exportDiagnostics(String key) {
        return protocol.exportDiagnostics(key).thenApply(mapper::attachment);
    }
}
