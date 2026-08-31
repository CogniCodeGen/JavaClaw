package com.javaclaw.sdk.model;

import java.util.List;

/**
 * Skill 编辑器使用的类型化内容；原始声明保留未知字段，保存时仅替换显式编辑的正文和资源。
 *
 * @param skill 原始声明与 revision，新建时可使用 revision 为 0 的草稿
 * @param instructions 完整正文，不静默截断
 * @param resources Bundle 内资源；不包含客户端绝对路径
 */
public record SkillContentInfo(SkillInfo skill, String instructions, List<SkillResourceInfo> resources) {
    /** 复制资源集合，编辑过程中不会修改 SDK 已返回的历史快照。 */
    public SkillContentInfo {
        resources = List.copyOf(resources);
    }
}
