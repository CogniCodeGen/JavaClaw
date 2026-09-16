package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.IsolatedServiceCallScope;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;

/** Site 领域只注册业务操作；页面、进程、秘密与授权复核由现有宿主隔离服务承担。 */
final class SiteBrowserContributions {
    private ExtensionPayloadCodec payloads;

    List<ExtensionContribution> start(ExtensionContext context, long revision) {
        payloads = context.payloads();
        List<ExtensionContribution> contributions = new ArrayList<>();
        contributions.add(new ExtensionContributions.Query(
                "site.interactive.query",
                Set.of("browser.status", "browser.grants", "browser.grant.preview"),
                this::invoke));
        contributions.add(new ExtensionContributions.Command(
                "site.interactive.command",
                Set.of(
                        "browser.open",
                        "browser.act",
                        "browser.takeover",
                        "browser.return",
                        "browser.close",
                        "browser.save",
                        "browser.capture",
                        "browser.login.forms",
                        "browser.grant.confirm",
                        "browser.grant.revoke"),
                this::invoke));
        contributions.add(tool(
                "browser_open", "打开独立浏览器或导航当前对话页面；新来源须用户授权。", ToolRisk.NETWORK, SiteBrowserSchemas.open(), revision));
        contributions.add(tool(
                "browser_act",
                "基于最近页面元素引用执行操作；不要使用宿主路径或输入凭据。结果未知时不得自动重试。",
                ToolRisk.EXTERNAL_EFFECT,
                SiteBrowserSchemas.act(),
                revision));
        contributions.add(tool(
                "browser_screenshot",
                "读取当前页面截图，返回带观察身份的图片附件；图片坐标必须使用该帧。",
                ToolRisk.READ_ONLY,
                SiteBrowserSchemas.empty(),
                revision));
        contributions.add(tool(
                "browser_tabs",
                "观察当前页面与最多八个标签页；元素身份只在当前页面代次有效。",
                ToolRisk.READ_ONLY,
                SiteBrowserSchemas.empty(),
                revision));
        contributions.add(tool(
                "browser_fill_account",
                "将当前账号凭据填入绑定 Origin 的有效登录表单；参数只包含元素身份，不提供密码。",
                ToolRisk.EXTERNAL_EFFECT,
                SiteBrowserSchemas.credentials(),
                revision));
        return List.copyOf(contributions);
    }

    private ExtensionContribution tool(
            String name, String description, ToolRisk risk, Map<String, Object> schema, long revision) {
        ToolDescriptor descriptor = new ToolDescriptor(
                new ToolIdentity(BuiltinExtensionIds.SITE, name, revision),
                description,
                payloads.encode(schema),
                payloads.encode(Map.of("type", "object")),
                risk,
                Set.of("browser", "https", "site"));
        return new ExtensionContributions.Tool("site.interactive." + name, descriptor, this::invoke);
    }

    private ExtensionResponse invoke(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        String operation = request.operation().startsWith("site.interactive.")
                ? request.operation().substring("site.interactive.".length())
                : request.operation();
        var invocation = new BrowserCommands.Invocation(operation, request.payload());
        var result = context.services()
                .invoke(new IsolatedServiceInvocation(
                        context.extension().id(),
                        context.workspaceId(),
                        context.effectivePermissions(),
                        BrowserCommands.SERVICE,
                        payloads.encode(invocation),
                        context.cancellation(),
                        IsolatedServiceCallScope.from(request)));
        return new ExtensionResponse(result, request.expectedRevision());
    }
}
