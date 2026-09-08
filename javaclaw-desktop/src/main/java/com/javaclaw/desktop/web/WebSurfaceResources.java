package com.javaclaw.desktop.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 构造完全离线的受信任页面；CSP 不允许网页网络、插件、frame 或用户正文脚本。 */
final class WebSurfaceResources {
    private WebSurfaceResources() {}

    static String page(String kind) {
        String nonce = UUID.randomUUID().toString();
        String library = kind.equals("graph") ? "vendor/cytoscape-3.33.1.min.js" : "vendor/highlight-11.11.1.min.js";
        return "<!doctype html><html><head><meta charset=\"UTF-8\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "script-src 'nonce-" + nonce + "'; style-src 'unsafe-inline'; img-src data: blob:; "
                + "connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">"
                + "<style>" + read("surface.css") + "</style></head><body>"
                + "<main id=\"surface\" role=\"main\"></main>"
                + script(nonce, read(library)) + script(nonce, read("surface.js"))
                + script(nonce, read(kind + ".js")) + "</body></html>";
    }

    private static String script(String nonce, String source) {
        return "<script nonce=\"" + nonce + "\">" + source.replace("</script", "<\\/script") + "</script>";
    }

    private static String read(String name) {
        try (InputStream input = WebSurfaceResources.class.getResourceAsStream("/web/" + name)) {
            if (input == null) {
                throw new IllegalStateException("缺少页面资源: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("页面资源无法读取", failure);
        }
    }
}
