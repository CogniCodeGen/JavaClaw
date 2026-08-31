package com.javaclaw.agent.tools;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.agent.tool.BrowserGateway;
import com.javaclaw.agent.tool.RegisteredTool;
import com.javaclaw.agent.tool.ToolHandler;
import com.javaclaw.agent.tool.ToolOrigin;
import com.javaclaw.agent.tool.ToolRisk;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** Browser rendering tool backed by brokered fetch and a networkless Browser Service. */
public final class BrowserSnapshotTool {
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["url"],
             "properties":{
              "url":{"type":"string","minLength":8,"maxLength":8192},
              "screenshot":{"type":"boolean"},
              "maxTextChars":{"type":"integer","minimum":100,"maximum":100000}}}
            """;

    private BrowserSnapshotTool() {}

    /** 注册进程外浏览器快照工具；页面访问和截图仍受 Broker 与沙箱约束。 */
    public static RegisteredTool create(BrowserGateway browser, SandboxPolicy ceiling) {
        Objects.requireNonNull(browser, "browser");
        ToolDescriptor descriptor = new ToolDescriptor(
                "browser_snapshot", "Render one approved public HTTP(S) page in the isolated Browser Service.", SCHEMA);
        return new RegisteredTool(descriptor, ToolOrigin.BUILTIN, ToolRisk.HIGH, true, ceiling, context -> {
            URI uri = parse(context.arguments().path("url").asText());
            boolean screenshot = context.arguments().path("screenshot").asBoolean(false);
            int maximum = context.arguments().path("maxTextChars").asInt(20_000);
            BrowserGateway.Snapshot snapshot = browser.snapshot(uri, screenshot, maximum);
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            result.put("status", Integer.toString(snapshot.statusCode()));
            result.put("finalUri", snapshot.finalUri().toString());
            result.put("title", snapshot.title());
            if (snapshot.screenshotAttachmentSha256() != null) {
                result.put("screenshotAttachmentSha256", snapshot.screenshotAttachmentSha256());
            }
            ThreadItem item = screenshot && snapshot.screenshotAttachmentSha256() != null
                    ? new ThreadItem.ImageView(
                            "attachment:sha256:" + snapshot.screenshotAttachmentSha256(), snapshot.title())
                    : new ThreadItem.DynamicToolCall("browser_snapshot", Map.copyOf(result));
            String model = "HTTP " + snapshot.statusCode() + " " + snapshot.finalUri() + "\nTitle: " + snapshot.title()
                    + "\n" + snapshot.text();
            return new ToolHandler.Result(item, model);
        });
    }

    private static URI parse(String value) {
        URI uri;
        try {
            uri = URI.create(value).normalize();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("url is invalid", failure);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!("http".equals(scheme) || "https".equals(scheme))
                || uri.isOpaque()
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("url must be an HTTP(S) origin without credentials or fragments");
        }
        return uri;
    }
}
