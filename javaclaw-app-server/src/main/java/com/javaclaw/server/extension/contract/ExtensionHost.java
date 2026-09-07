package com.javaclaw.server.extension.contract;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** Core 调用 Extension 的统一边界；内置与第三方实现不得泄漏各自装载方式。 */
public interface ExtensionHost extends AutoCloseable {
    /**
     * 列出当前扩展。
     *
     * @return 稳定排序的扩展摘要
     */
    List<ExtensionRpcContracts.Summary> list();

    /**
     * 列出当前可发现工具。
     *
     * @return 全局名称唯一的工具描述
     */
    List<ToolDescriptor> tools();

    /**
     * 执行冻结目录中的工具。
     *
     * @param request 调用请求
     * @param frozenDescriptor Turn 启动时冻结的描述
     * @param callerPermissions 调用时重新解析的权限
     * @param cancellation 取消信号
     * @return 扩展响应
     * @throws Exception 扩展执行失败
     */
    ExtensionResponse executeTool(
            ToolCallRequest request,
            ToolDescriptor frozenDescriptor,
            PermissionProfile callerPermissions,
            CancellationToken cancellation)
            throws Exception;

    /**
     * 执行工具并返回平台产生的附加事实；第三方扩展默认没有平台事实权限。
     *
     * @param request 真实工具调用
     * @param frozenDescriptor 冻结工具描述
     * @param callerPermissions 实时有效权限
     * @param cancellation 取消信号
     * @return 扩展结果与平台事实
     * @throws Exception 执行失败
     */
    default GovernedExtensionResponse executeToolWithFacts(
            ToolCallRequest request,
            ToolDescriptor frozenDescriptor,
            PermissionProfile callerPermissions,
            CancellationToken cancellation)
            throws Exception {
        return new GovernedExtensionResponse(
                executeTool(request, frozenDescriptor, callerPermissions, cancellation), List.of());
    }

    /**
     * 执行无副作用查询。
     *
     * @param call 查询参数
     * @return 扩展响应
     * @throws Exception 执行失败
     */
    ExtensionResponse query(ExtensionRpcContracts.CallPayload call) throws Exception;

    /**
     * 执行带幂等身份的命令。
     *
     * @param call 命令参数
     * @param idempotencyKey 幂等键
     * @param expectedRevision 期望资源版本
     * @return 扩展响应
     * @throws Exception 执行失败
     */
    ExtensionResponse command(ExtensionRpcContracts.CallPayload call, String idempotencyKey, long expectedRevision)
            throws Exception;

    /**
     * 读取公开 Schema。
     *
     * @param extensionId 扩展标识
     * @param schemaId Schema 标识
     * @return Schema
     */
    ExtensionSchema schema(String extensionId, String schemaId);

    /**
     * 列出声明式页面。
     *
     * @param extensionId 可选扩展过滤
     * @return 页面文档
     */
    List<ExtensionRpcContracts.ViewDocument> views(Optional<String> extensionId);

    /** 关闭 Host 持有的资源。 */
    @Override
    void close() throws Exception;
}
