package com.javaclaw.protocol;

/**
 * 不可变 Skill 资源；路径仅为 Bundle 内标识，不能用作客户端或宿主绝对路径。
 *
 * @param path Bundle 内相对路径
 * @param mediaType 媒体类型
 * @param content 有界资源正文
 * @param executable 是否显式声明为脚本；仍需沙箱和治理
 */
public record WireSkillResource(String path, String mediaType, String content, boolean executable) {}
