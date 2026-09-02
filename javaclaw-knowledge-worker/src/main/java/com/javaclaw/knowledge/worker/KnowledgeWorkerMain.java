package com.javaclaw.knowledge.worker;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.javaclaw.builtin.contracts.KnowledgeWorkerProtocol;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.LengthPrefixedFraming;

/** 一次性 Knowledge Worker 入口；stdin/stdout 只承载私有长度前缀帧。 */
public final class KnowledgeWorkerMain {
    private KnowledgeWorkerMain() {}

    /**
     * 读取一个命令与原始附件，写入一个脱敏结果后退出。
     *
     * @param args 不接受参数
     * @throws Exception framing 或管道写入失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("Knowledge Worker does not accept arguments");
        }
        run(System.in, System.out);
    }

    static void run(InputStream input, OutputStream output) throws Exception {
        CanonicalJson json = new CanonicalJson();
        byte[] requestFrame = LengthPrefixedFraming.read(input, KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
        KnowledgeWorkerProtocol.Request request = json.decode(
                json.parse(new String(requestFrame, StandardCharsets.UTF_8)), KnowledgeWorkerProtocol.Request.class);
        byte[] content = LengthPrefixedFraming.read(input, KnowledgeWorkerProtocol.MAXIMUM_CONTENT_BYTES);
        KnowledgeWorkerProtocol.Response response;
        try {
            if (content.length != request.contentBytes()) {
                response = KnowledgeWorkerProtocol.Response.failure("KNOWLEDGE_CONTENT_LENGTH_MISMATCH");
            } else {
                response = new KnowledgeRequestHandler(new KnowledgeExtractor()).handle(request, content);
            }
        } finally {
            Arrays.fill(content, (byte) 0);
        }
        byte[] encoded = json.encode(response).json().getBytes(StandardCharsets.UTF_8);
        LengthPrefixedFraming.write(output, encoded, KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
    }
}
