package com.javaclaw.builtin.contracts;

import java.util.Objects;

import com.javaclaw.api.AttachmentRef;

/** v5 Skill Markdown 与 Bundle 的严格导入导出契约。 */
public final class SkillTransferContracts {
    /** v5 Markdown Skill 的固定 MIME 类型。 */
    public static final String MARKDOWN_MEDIA_TYPE = "text/markdown";

    /** v5 Skill Bundle 的固定 MIME 类型；不接受旧 Bundle 或通用 ZIP。 */
    public static final String BUNDLE_MEDIA_TYPE = "application/vnd.javaclaw.skill-v5+zip";

    /** 单次 Skill 导入 Attachment 的最大原始字节数。 */
    public static final long MAXIMUM_IMPORT_BYTES = 20L * 1024 * 1024;

    private SkillTransferContracts() {}

    /** v5 Skill 可移植格式。 */
    public enum TransferFormat {
        /** 仅携带文本元数据和指令，不携带资源。 */
        MARKDOWN(MARKDOWN_MEDIA_TYPE, ".skill.md"),
        /** 携带 manifest、指令和全部资源的 v5 ZIP Bundle。 */
        BUNDLE(BUNDLE_MEDIA_TYPE, ".skill-v5.zip");

        private final String mediaType;
        private final String fileSuffix;

        TransferFormat(String mediaType, String fileSuffix) {
            this.mediaType = mediaType;
            this.fileSuffix = fileSuffix;
        }

        /** @return 固定 MIME 类型 */
        public String mediaType() {
            return mediaType;
        }

        /** @return 安全导出文件后缀 */
        public String fileSuffix() {
            return fileSuffix;
        }

        /**
         * 从严格 MIME 类型识别 v5 格式。
         *
         * @param mediaType Attachment MIME 类型
         * @return 唯一 v5 格式
         */
        public static TransferFormat fromMediaType(String mediaType) {
            String checked = ContractValidation.text(mediaType, "mediaType");
            for (TransferFormat format : values()) {
                if (format.mediaType.equals(checked)) {
                    return format;
                }
            }
            throw new IllegalArgumentException("Attachment is not a v5 Skill Markdown or Bundle");
        }
    }

    /**
     * 从 Workspace-owned Core Attachment 导入 Skill Draft。
     *
     * @param attachment 已完成上传的 v5 Markdown 或 Bundle
     */
    public record ImportRequest(AttachmentRef attachment) {
        /** 校验格式和有界大小；Workspace 所有权由服务端端口核验。 */
        public ImportRequest {
            Objects.requireNonNull(attachment, "attachment");
            TransferFormat.fromMediaType(attachment.mediaType());
            if (attachment.sizeBytes() < 1 || attachment.sizeBytes() > MAXIMUM_IMPORT_BYTES) {
                throw new IllegalArgumentException("Skill import Attachment size exceeds the supported range");
            }
        }
    }

    /**
     * Skill 导入结果。
     *
     * @param draft 已创建或替换的 Draft
     * @param format 实际解析格式
     */
    public record ImportResult(SkillContracts.Draft draft, TransferFormat format) {
        /** 校验结果。 */
        public ImportResult {
            Objects.requireNonNull(draft, "draft");
            Objects.requireNonNull(format, "format");
        }
    }

    /**
     * 导出当前精确 Published Skill。
     *
     * @param id Skill 标识
     * @param format v5 Markdown 或 Bundle
     */
    public record ExportRequest(String id, TransferFormat format) {
        /** 校验标识和格式。 */
        public ExportRequest {
            id = ContractValidation.text(id, "id");
            Objects.requireNonNull(format, "format");
        }
    }

    /**
     * Skill 导出产生的 Core Attachment。
     *
     * @param attachment Workspace-owned 内容寻址引用
     * @param skillId Skill 标识
     * @param publishedRevision 导出的精确 Published revision
     * @param digest Published 内容摘要
     * @param format 导出格式
     */
    public record ExportResult(
            AttachmentRef attachment, String skillId, long publishedRevision, String digest, TransferFormat format) {
        /** 校验引用与 Published 身份。 */
        public ExportResult {
            Objects.requireNonNull(attachment, "attachment");
            skillId = ContractValidation.text(skillId, "skillId");
            publishedRevision = ContractValidation.revision(publishedRevision);
            digest = ContractDigests.requireSha256(digest, "digest");
            Objects.requireNonNull(format, "format");
            if (!attachment.mediaType().equals(format.mediaType())) {
                throw new IllegalArgumentException("Skill export Attachment media type does not match its format");
            }
        }
    }
}
