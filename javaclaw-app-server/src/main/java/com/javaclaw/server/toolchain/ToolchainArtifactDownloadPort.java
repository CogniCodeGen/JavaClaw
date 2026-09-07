package com.javaclaw.server.toolchain;

import java.io.OutputStream;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;

/** 服务端可信制品目录的流式下载端口；调用方负责摘要校验、临时文件与安装原子提交。 */
@FunctionalInterface
public interface ToolchainArtifactDownloadPort {
    /**
     * 将完整制品流式写入调用方拥有的目标；失败可留下部分内容，不代表安装完成。
     *
     * @param artifact 服务端可信目录提供的制品，不接受模型或客户端自定义 URL
     * @param target 有界安装暂存输出，下载器不关闭该流
     * @param cancellation 安装取消信号；取消后不再写入目标
     * @throws Exception 网络、协议、预算、取消或目标写入失败
     */
    void download(ToolchainArtifact artifact, OutputStream target, CancellationToken cancellation) throws Exception;
}
