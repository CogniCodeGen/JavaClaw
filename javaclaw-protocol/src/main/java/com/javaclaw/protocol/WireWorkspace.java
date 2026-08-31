package com.javaclaw.protocol;

import java.time.Instant;

/**
 * 工作区登记、版本与安全锁状态的公开视图，不授权客户端直接访问服务器文件系统。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param name 展示名称或资源名称；有效服务端响应中非空
 * @param root 服务端登记的规范化 Workspace 根目录
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param locked 是否因安全恢复等原因锁定工作区
 * @param lockReason 工作区锁定原因；未锁定时可为空
 * @param createdAt 创建时间；有效持久记录中非空
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record WireWorkspace(
        String id,
        String name,
        String root,
        long revision,
        boolean locked,
        String lockReason,
        Instant createdAt,
        Instant updatedAt) {}
