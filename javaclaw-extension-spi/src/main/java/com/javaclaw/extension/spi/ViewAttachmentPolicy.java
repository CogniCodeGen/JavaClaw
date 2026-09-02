package com.javaclaw.extension.spi;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ViewSchema v2 Attachment 字段允许的平台上传边界。
 *
 * <p>媒体类型只能是精确 MIME 类型或 {@code type/*}，不接受文件路径、URL 或可执行匹配表达式。大小是原始文件字节数，平台必须在读取和提交前同时校验。
 *
 * @param acceptedMediaTypes 允许的精确 MIME 类型或 {@code type/*}
 * @param maximumBytes 单文件最大原始字节数
 */
public record ViewAttachmentPolicy(Set<String> acceptedMediaTypes, long maximumBytes) {
    /** Core Attachment 当前允许的最大原始字节数。 */
    public static final long PLATFORM_MAXIMUM_BYTES = 64L * 1024 * 1024;

    private static final Pattern MEDIA_TYPE =
            Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/(?:[a-z0-9][a-z0-9!#$&^_.+-]{0,127}|\\*)");

    /** 校验媒体类型白名单与大小上限。 */
    public ViewAttachmentPolicy {
        acceptedMediaTypes = Set.copyOf(Objects.requireNonNull(acceptedMediaTypes, "acceptedMediaTypes")).stream()
                .map(ViewAttachmentPolicy::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (acceptedMediaTypes.isEmpty()) {
            throw new IllegalArgumentException("acceptedMediaTypes must not be empty");
        }
        if (maximumBytes < 1 || maximumBytes > PLATFORM_MAXIMUM_BYTES) {
            throw new IllegalArgumentException("maximumBytes exceeds the platform Attachment limit");
        }
    }

    /**
     * 判断一个 MIME 类型是否在白名单内。
     *
     * @param mediaType 待判断 MIME 类型；参数部分会被忽略
     * @return 精确类型或同主类型通配符匹配时为 true
     */
    public boolean accepts(String mediaType) {
        String normalized = baseType(mediaType);
        int separator = normalized.indexOf('/');
        return acceptedMediaTypes.contains(normalized)
                || acceptedMediaTypes.contains(normalized.substring(0, separator) + "/*");
    }

    private static String normalize(String value) {
        String normalized = baseType(value);
        if (!MEDIA_TYPE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("accepted media type is invalid: " + normalized);
        }
        return normalized;
    }

    private static String baseType(String value) {
        String normalized = Objects.requireNonNull(value, "mediaType")
                .split(";", 2)[0]
                .strip()
                .toLowerCase(Locale.ROOT);
        if (!normalized.contains("/")) {
            throw new IllegalArgumentException("mediaType must contain a subtype");
        }
        return normalized;
    }
}
