package com.javaclaw.agent.tool;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.knowledge.DocumentExtractionGateway;
import com.javaclaw.agent.model.ModelGateway;
import com.javaclaw.agent.model.ModelInvocationService;
import com.javaclaw.agent.model.ResponseContract;
import com.javaclaw.agent.prompt.PromptPurpose;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.core.api.ModelImage;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 显式 OCR：仅能读取本 Thread 已拥有的附件，PDF 渲染在 Worker，视觉调用共享当前 Turn 预算。 */
public final class DocumentToolProvider implements ToolProvider {
    private static final String INPUT = """
            {"type":"object","additionalProperties":false,"required":["sha256","firstPage","pageCount"],"properties":{
            "sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"firstPage":{"type":"integer","minimum":1},
            "pageCount":{"type":"integer","minimum":1,"maximum":20}}}
            """;
    private static final ToolDescriptor OCR =
            new ToolDescriptor("ocr_document", "忠实提取已提交 PDF 或图片的文字；每次最多二十页，返回页码与不确定内容。", INPUT);
    private final AttachmentRepository attachments;
    private final DocumentExtractionGateway documents;
    private final ModelInvocationService models;
    private final SandboxPolicy ceiling;

    /** Worker 和模型均由装配注入；没有 Worker 时不应发布该 Provider。 */
    public DocumentToolProvider(
            AttachmentRepository attachments,
            DocumentExtractionGateway documents,
            ModelGateway models,
            SandboxPolicy ceiling) {
        this.attachments = java.util.Objects.requireNonNull(attachments);
        this.documents = java.util.Objects.requireNonNull(documents);
        this.models = new ModelInvocationService(models);
        this.ceiling = java.util.Objects.requireNonNull(ceiling);
    }

    @Override
    public String id() {
        return "documents";
    }

    @Override
    public List<ToolDescriptor> catalog() {
        return List.of(OCR);
    }

    @Override
    public List<RegisteredTool> tools(TurnExecutionContext turn) {
        var allowed = new LinkedHashSet<String>();
        turn.turn().input().forEach(input -> {
            if (input instanceof TurnInput.AttachmentRef reference) {
                allowed.add(reference.sha256());
            }
        });
        turn.priorItems().forEach(stored -> {
            if (stored.item() instanceof ThreadItem.UserMessage message) {
                message.attachments().forEach(reference -> allowed.add(reference.sha256()));
            }
        });
        Set<String> fixed = Set.copyOf(allowed);
        return List.of(new RegisteredTool(OCR, ToolOrigin.BUILTIN, ToolRisk.LOW, false, ceiling, context -> {
                    String hash = context.arguments().path("sha256").asText();
                    if (!fixed.contains(hash)) {
                        throw new IllegalArgumentException("OCR attachment is not owned by this Thread snapshot");
                    }
                    int first = context.arguments().path("firstPage").asInt();
                    int count = context.arguments().path("pageCount").asInt();
                    var metadata = attachments.findAttachment(hash).orElseThrow();
                    byte[] bytes;
                    try (var stream = attachments.openAttachment(hash)) {
                        bytes = stream.readNBytes(256 * 1024 * 1024 + 1);
                    }
                    if (bytes.length != metadata.sizeBytes() || !hash.equals(digest(bytes))) {
                        throw new IllegalArgumentException("OCR attachment length or SHA-256 mismatch");
                    }
                    List<DocumentExtractionGateway.PageImage> pages;
                    boolean pdf = metadata.mediaType().split(";", 2)[0].equals("application/pdf");
                    if (pdf) {
                        pages = documents.render(bytes, first, count);
                    } else {
                        if (first != 1
                                || count != 1
                                || !Set.of("image/png", "image/jpeg").contains(metadata.mediaType())) {
                            throw new IllegalArgumentException(
                                    "image OCR must select page one; supported inputs are PDF, PNG and JPEG");
                        }
                        pages = List.of(new DocumentExtractionGateway.PageImage(1, bytes));
                    }
                    var call = context.call();
                    var nested = new TurnExecutionContext(
                            call.thread(), call.turn(), List.of(), new AtomicBoolean(), null, call.scope());
                    var output = new StringBuilder();
                    for (var page : pages) {
                        call.scope().check();
                        String type = pdf ? "image/png" : metadata.mediaType();
                        var image = new ModelImage(digest(page.png()), type, page.png());
                        var dialogue = List.of(new ModelMessage(
                                ModelMessage.Role.USER,
                                "提取此页可见文字，不补写。来源 " + hash + "；页码 " + page.page(),
                                null,
                                null,
                                List.of(),
                                List.of(image)));
                        var contract = new OcrContract();
                        var prompt = models.withContract(
                                models.prepare(
                                        PromptPurpose.OCR,
                                        call.config(),
                                        List.of(),
                                        List.of(),
                                        com.javaclaw.agent.prompt.AgentsInstructionResolution.empty(
                                                call.thread().workingDirectory()),
                                        dialogue),
                                contract);
                        var response = models.complete(nested, prompt, context.events());
                        var pageResult = models.validateOrRepair(
                                nested, prompt, prompt.messages(), response, contract, context.events());
                        String content = "第 " + page.page() + " 页\n" + pageResult.text()
                                + (pageResult.uncertain().isEmpty()
                                        ? ""
                                        : "\n不确定内容：" + String.join("；", pageResult.uncertain()));
                        context.events()
                                .append(new ThreadItem.Artifact(
                                        "ocr-" + call.call().id() + "-" + page.page(),
                                        "ocr",
                                        "OCR 第 " + page.page() + " 页",
                                        1,
                                        content,
                                        List.of(hash + "#page=" + page.page())));
                        if (output.length() + content.length() < 24_000) {
                            output.append(content).append('\n');
                        } else {
                            output.append("其余完整文字已保存为分页 Artifact，请分批处理。\n");
                        }
                    }
                    return new ToolHandler.Result(
                            new ThreadItem.DynamicToolCall(
                                    "ocr_document",
                                    Map.of(
                                            "sha256",
                                            hash,
                                            "firstPage",
                                            Integer.toString(first),
                                            "pages",
                                            Integer.toString(pages.size()),
                                            "status",
                                            "completed")),
                            output.toString());
                })
                .readOnly());
    }

    private static String digest(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record OcrResult(String text, List<String> uncertain) {}

    private static final class OcrContract implements ResponseContract<OcrResult> {
        private static final ObjectMapper JSON =
                new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

        @Override
        public String id() {
            return "ocr-page-v1";
        }

        @Override
        public String schema() {
            return "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"text\",\"uncertain\"],\"properties\":{\"text\":{\"type\":\"string\",\"maxLength\":24000},\"uncertain\":{\"type\":\"array\",\"maxItems\":100,\"items\":{\"type\":\"string\"}}}}";
        }

        @Override
        public OcrResult decode(String content) {
            try {
                var value = JSON.readTree(content);
                if (!value.isObject()
                        || value.size() != 2
                        || !value.path("text").isTextual()
                        || value.path("text").asText().length() > 24_000
                        || !value.path("uncertain").isArray()
                        || value.path("uncertain").size() > 100) {
                    throw new IllegalArgumentException("invalid OCR response");
                }
                var uncertain = new ArrayList<String>();
                for (var entry : value.path("uncertain")) {
                    if (!entry.isTextual() || entry.asText().length() > 1_000) {
                        throw new IllegalArgumentException("invalid uncertainty entry");
                    }
                    uncertain.add(entry.asText());
                }
                return new OcrResult(value.path("text").asText(), List.copyOf(uncertain));
            } catch (java.io.IOException failure) {
                throw new IllegalArgumentException("invalid OCR JSON", failure);
            }
        }
    }
}
